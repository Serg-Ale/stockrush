package dev.stockrush.payment;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides the payment for each {@code order.created} event and publishes the result. The decision is stored per
 * order before it is published, so a redelivered event gets the same answer and the result is simply sent again:
 * at-least-once on both sides, idempotent for the consumer in {@code api}.
 */
@Component
class OrderCreatedListener {

	static final String CORRELATION_ID_HEADER = "x-correlation-id";

	private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

	static final String ORDERS_EXCHANGE = "orders";

	static final String ORDER_CREATED = "order.created";

	static final String PAYMENTS_EXCHANGE = "payments";

	static final String PAYMENT_RESULT = "payment.result";

	static final String DEAD_LETTER_QUEUE = "order.created.dlq";

	/** Why a message went straight to the DLQ without retries. */
	static final String FAILURE_CAUSE_HEADER = "x-failure-cause";

	private final JdbcClient jdbc;

	private final RabbitTemplate rabbit;

	private final JsonMapper json;

	private final Duration confirmTimeout;

	private final ChaosSettings chaos;

	private final List<Duration> retrySteps;

	private final int maxAttempts;

	private final Counter retries;

	private final Counter permanentFailures;

	OrderCreatedListener(JdbcClient jdbc, RabbitTemplate rabbit, JsonMapper json, ChaosSettings chaos,
			@Value("${app.payment.confirm-timeout:PT5S}") Duration confirmTimeout,
			@Value("${app.payment.retry.steps:PT1S,PT2S,PT4S}") List<Duration> retrySteps,
			@Value("${app.payment.retry.max-attempts:4}") int maxAttempts, MeterRegistry meters) {
		if (retrySteps.size() < maxAttempts - 1) {
			throw new IllegalArgumentException("Need one retry step per retry: " + (maxAttempts - 1));
		}
		this.jdbc = jdbc;
		this.rabbit = rabbit;
		this.json = json;
		this.chaos = chaos;
		this.confirmTimeout = confirmTimeout;
		this.retrySteps = retrySteps;
		this.maxAttempts = maxAttempts;
		this.retries = Counter.builder("payment_retries_total")
				.description("Payment attempts that failed temporarily and were parked for another try")
				.register(meters);
		this.permanentFailures = Counter.builder("payment_permanent_failures_total")
				.description("order.created messages that can never succeed, sent straight to the dead-letter queue")
				.register(meters);
	}

	@RabbitListener(queues = ORDER_CREATED)
	void onOrderCreated(Message message) {
		var correlationId = message.getMessageProperties().<String>getHeader(CORRELATION_ID_HEADER);
		if (correlationId != null) {
			MDC.put("correlationId", correlationId);
		}
		try {
			process(message);
		}
		finally {
			MDC.remove("correlationId");
		}
	}

	private void process(Message message) {
		try {
			decideAndAnswer(message);
		}
		catch (PermanentFailure e) {
			rejectPermanently(message, e);
		}
	}

	private void decideAndAnswer(Message message) {
		var event = parse(message);
		var attempt = attempt(message);
		var result = decide(event.orderId(), event.paymentToken(), attempt);
		if (result != null) {
			log.info("Payment for order {} decided on attempt {}: {}", event.orderId(), attempt, result);
			publishResult(event.orderId(), result, null);
		}
		else if (attempt < maxAttempts) {
			log.info("Payment for order {} failed temporarily on attempt {}; retrying", event.orderId(), attempt);
			retryLater(message, attempt);
		}
		else {
			log.warn("Payment for order {} exhausted {} attempts; dead-lettering", event.orderId(), attempt);
			giveUp(message, event.orderId());
		}
	}

	/** A message that can never succeed: no retry steps. Cancels the order when its id is readable (ADR 0001, 8). */
	private void rejectPermanently(Message message, PermanentFailure failure) {
		log.warn("Permanent failure, dead-lettering without retries (order {}): {}", failure.orderId, failure.getMessage());
		permanentFailures.increment();
		if (failure.orderId != null) {
			publishResult(failure.orderId, "EXHAUSTED", "INVALID_MESSAGE");
		}
		var cause = failure.getMessage();
		publish("", DEAD_LETTER_QUEUE, MessageBuilder.fromMessage(message)
				.setHeader(FAILURE_CAUSE_HEADER, cause.length() > 200 ? cause.substring(0, 200) : cause).build());
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

	/**
	 * Tells api first, then keeps the original message in the DLQ for inspection. The two publishes are not atomic:
	 * a crash in between redelivers the message and restarts the attempts (ADR 0001, consequences).
	 */
	private void giveUp(Message message, long orderId) {
		publishResult(orderId, "EXHAUSTED", null);
		publish("", DEAD_LETTER_QUEUE, MessageBuilder.fromMessage(message).build());
	}

	/** Parks the message in the wait queue for this attempt; its TTL then dead-letters it back to the main queue. */
	private void retryLater(Message message, int attempt) {
		var copy = MessageBuilder.fromMessage(message).build();
		publish("", waitQueue(attempt), copy);
		retries.increment();
	}

	private static String waitQueue(int attempt) {
		return ORDER_CREATED + ".wait." + attempt;
	}

	private OrderCreated parse(Message message) {
		JsonNode tree;
		try {
			tree = json.readTree(message.getBody());
		}
		catch (JacksonException e) {
			throw new PermanentFailure("Body is not valid JSON", null);
		}
		var id = tree.path("orderId");
		if (!id.isIntegralNumber()) {
			throw new PermanentFailure("orderId is missing or not a number", null);
		}
		var orderId = id.asLong();
		var token = tree.path("paymentToken");
		if (!token.isString()) {
			throw new PermanentFailure("paymentToken is missing or not a string", orderId);
		}
		return new OrderCreated(orderId, token.asString());
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
			case "tok_dead" -> null;
			case "tok_slow" -> slowApproval();
			default -> throw new PermanentFailure("Unsupported payment token: " + token, orderId);
		};
		if (result == null || chaos.shouldFail()) {
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

	/** tok_slow answers APPROVED, but only after the configured latency. */
	private String slowApproval() {
		try {
			Thread.sleep(chaos.latency());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while simulating latency", e);
		}
		return "APPROVED";
	}

	/** {@code reason} refines an EXHAUSTED result for operators (INVALID_MESSAGE); null otherwise. */
	private void publishResult(long orderId, String result, String reason) {
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		var correlationId = MDC.get("correlationId");
		if (correlationId != null) {
			properties.setHeader(CORRELATION_ID_HEADER, correlationId);
		}
		Message message = MessageBuilder
				.withBody(json.writeValueAsString(reason == null ? Map.of("orderId", orderId, "result", result)
						: Map.of("orderId", orderId, "result", result, "reason", reason)).getBytes(StandardCharsets.UTF_8))
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

	/** A message that no retry can fix. {@code orderId} is null when it could not be read. */
	private static final class PermanentFailure extends RuntimeException {

		private final Long orderId;

		PermanentFailure(String cause, Long orderId) {
			super(cause);
			this.orderId = orderId;
		}

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

	/** Inspection only: nobody consumes it, and it keeps at most 1000 messages (the oldest are dropped). */
	@Bean
	Queue deadLetterQueue() {
		return QueueBuilder.durable(DEAD_LETTER_QUEUE).maxLength(1000).build();
	}

	@Bean
	MeterBinder deadLetterDepth(RabbitTemplate rabbit) {
		return registry -> Gauge
				.builder("payment_dlq_depth", () -> rabbit.execute(channel -> channel.messageCount(DEAD_LETTER_QUEUE)))
				.description("Messages waiting in the payment dead-letter queue")
				.register(registry);
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
