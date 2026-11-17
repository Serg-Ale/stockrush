package dev.stockrush.payment;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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

	OrderCreatedListener(JdbcClient jdbc, RabbitTemplate rabbit, JsonMapper json,
			@Value("${app.payment.confirm-timeout:PT5S}") Duration confirmTimeout) {
		this.jdbc = jdbc;
		this.rabbit = rabbit;
		this.json = json;
		this.confirmTimeout = confirmTimeout;
	}

	@RabbitListener(queues = ORDER_CREATED)
	void onOrderCreated(Message message) {
		var event = parse(message);
		var result = decide(event.orderId(), event.paymentToken());
		publish(event.orderId(), result);
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

	/** Stores the decision the first time an order is seen and returns the stored one afterwards. */
	private String decide(long orderId, String token) {
		var result = switch (token) {
			case "tok_approve" -> "APPROVED";
			case "tok_decline" -> "DECLINED";
			default -> throw new AmqpRejectAndDontRequeueException("Unsupported payment token: " + token);
		};
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

	private void publish(long orderId, String result) {
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		Message message = MessageBuilder
				.withBody(json.writeValueAsString(Map.of("orderId", orderId, "result", result))
						.getBytes(StandardCharsets.UTF_8))
				.andProperties(properties)
				.build();
		rabbit.invoke(operations -> {
			operations.send(PAYMENTS_EXCHANGE, PAYMENT_RESULT, message);
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
