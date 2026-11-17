package dev.stockrush.payment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides the payment for each {@code order.created} event and publishes the result. The decision is stored per
 * order before it is published, so a redelivered event gets the same answer and the result is simply sent again:
 * at-least-once on both sides, idempotent for the consumer in {@code api}.
 */
@Component
class OrderCreatedListener {

	static final String ORDERS_EXCHANGE = "orders";

	static final String ORDER_CREATED = "order.created";

	static final String PAYMENTS_EXCHANGE = "payments";

	static final String PAYMENT_RESULT = "payment.result";

	private final JdbcClient jdbc;

	private final RabbitTemplate rabbit;

	private final JsonMapper json;

	private final Duration confirmTimeout;

	private final List<Duration> retrySteps;

	private final int maxAttempts;

	OrderCreatedListener(JdbcClient jdbc, RabbitTemplate rabbit, JsonMapper json,
			@Value("${app.payment.confirm-timeout:PT5S}") Duration confirmTimeout,
			@Value("${app.payment.retry.steps:PT1S,PT2S,PT4S}") List<Duration> retrySteps,
			@Value("${app.payment.retry.max-attempts:4}") int maxAttempts) {
		if (retrySteps.size() < maxAttempts - 1) {
			throw new IllegalArgumentException("Need one retry step per retry: " + (maxAttempts - 1));
		}
		this.jdbc = jdbc;
		this.rabbit = rabbit;
		this.json = json;
		this.confirmTimeout = confirmTimeout;
		this.retrySteps = retrySteps;
		this.maxAttempts = maxAttempts;
	}

	@RabbitListener(queues = ORDER_CREATED)
	void onOrderCreated(Message message) {
		var event = parse(message);
		var attempt = attempt(message);
		var result = decide(event.orderId(), event.paymentToken(), attempt);
		if (result != null) {
			publishResult(event.orderId(), result);
		}
		else if (attempt < maxAttempts) {
			retryLater(message, attempt);
		}
		else {
			// Out of attempts: EXHAUSTED result and DLQ are the next ticket.
			throw new AmqpRejectAndDontRequeueException("Attempts exhausted for order " + event.orderId());
		}
	}

	/**
	 * 1 for the first delivery; after a wait queue's TTL sent the message back, one more than that queue's step.
	 * The broker stamps {@code x-last-death-queue} on the message when it dead-letters it, so no retry state is kept
	 * in the database. (The accumulated {@code x-death} count cannot be used: the broker drops that header when a
	 * client republishes the message, which is how a message reaches the next wait queue.)
	 */
	private int attempt(Message message) {
		var queue = message.getMessageProperties().getHeader("x-last-death-queue");
		if (queue instanceof String name && name.startsWith(ORDER_CREATED + ".wait.")) {
			return Integer.parseInt(name.substring((ORDER_CREATED + ".wait.").length())) + 1;
		}
		return 1;
	}

	/** Parks the message in the wait queue for this attempt; its TTL then dead-letters it back to the main queue. */
	private void retryLater(Message message, int attempt) {
		var copy = MessageBuilder.fromMessage(message).build();
		publish("", waitQueue(attempt), copy);
	}

	private static String waitQueue(int attempt) {
		return ORDER_CREATED + ".wait." + attempt;
	}

	private OrderCreated parse(Message message) {
		try {
			var tree = json.readTree(message.getBody());
			return new OrderCreated(tree.required("orderId").asLong(),
					tree.required("paymentToken").asString());
		}
		catch (JacksonException | IllegalArgumentException e) {
			throw new AmqpRejectAndDontRequeueException("Invalid order.created message", e);
		}
	}

	/**
	 * Stores the decision the first time an order is seen and returns the stored one afterwards. Returns null for
	 * a temporary failure, which stores nothing.
	 */
	private String decide(long orderId, String token, int attempt) {
		var result = switch (token) {
			case "tok_approve" -> "APPROVED";
			case "tok_decline" -> "DECLINED";
			case "tok_flaky" -> attempt <= 2 ? null : "APPROVED";
			default -> throw new AmqpRejectAndDontRequeueException("Unsupported payment token: " + token);
		};
		if (result == null) {
			return null;
		}
		jdbc.sql("insert into payments (order_id, token, result) values (:id, :token, :result) "
				+ "on conflict (order_id) do nothing")
				.param("id", orderId)
				.param("token", token)
				.param("result", result)
				.update();
		return jdbc.sql("select result from payments where order_id = :id")
				.param("id", orderId)
				.query(String.class)
				.single();
	}

	private void publishResult(long orderId, String result) {
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		Message message = MessageBuilder
				.withBody(json.writeValueAsString(Map.of("orderId", orderId, "result", result))
						.getBytes(StandardCharsets.UTF_8))
				.andProperties(properties)
				.build();
		publish(PAYMENTS_EXCHANGE, PAYMENT_RESULT, message);
	}

	private void publish(String exchange, String routingKey, Message message) {
		rabbit.invoke(operations -> {
			operations.send(exchange, routingKey, message);
			operations.waitForConfirmsOrDie(confirmTimeout.toMillis());
			return null;
		});
	}

	private record OrderCreated(long orderId, String paymentToken) {
	}

	@Bean
	DirectExchange ordersExchange() {
		return new DirectExchange(ORDERS_EXCHANGE);
	}

	@Bean
	Queue orderCreatedQueue() {
		return QueueBuilder.durable(ORDER_CREATED).build();
	}

	@Bean
	Binding orderCreatedBinding(Queue orderCreatedQueue, DirectExchange ordersExchange) {
		return BindingBuilder.bind(orderCreatedQueue).to(ordersExchange).with(ORDER_CREATED);
	}

	/** One wait queue per backoff step: no consumer, messages expire after the step and return to the main queue. */
	@Bean
	Declarables retryWaitQueues(@Value("${app.payment.retry.steps:PT1S,PT2S,PT4S}") List<Duration> steps) {
		var queues = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
		for (var i = 0; i < steps.size(); i++) {
			queues.add(QueueBuilder.durable(waitQueue(i + 1))
					.ttl((int) steps.get(i).toMillis())
					.deadLetterExchange("")
					.deadLetterRoutingKey(ORDER_CREATED)
					.build());
		}
		return new Declarables(queues);
	}

	@Bean
	DirectExchange paymentsExchange() {
		return new DirectExchange(PAYMENTS_EXCHANGE);
	}

	@Bean
	Queue paymentResultQueue() {
		return QueueBuilder.durable(PAYMENT_RESULT).build();
	}

	@Bean
	Binding paymentResultBinding(Queue paymentResultQueue, DirectExchange paymentsExchange) {
		return BindingBuilder.bind(paymentResultQueue).to(paymentsExchange).with(PAYMENT_RESULT);
	}

}
