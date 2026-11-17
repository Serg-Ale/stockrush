package dev.stockrush.api.orders;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the payment service's results to orders through {@link OrderTransitions}, which are idempotent: a result
 * delivered twice, or one that arrives after the order is already final, changes nothing.
 */
@Component
class PaymentResultListener {

	private static final Logger log = LoggerFactory.getLogger(PaymentResultListener.class);

	static final String PAYMENTS_EXCHANGE = "payments";

	static final String CORRELATION_ID_HEADER = "x-correlation-id";

	static final String PAYMENT_RESULT = "payment.result";

	private final OrderTransitions transitions;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	private final Counter conflicts;

	PaymentResultListener(OrderTransitions transitions, JdbcClient jdbc, JsonMapper json, MeterRegistry meters) {
		this.transitions = transitions;
		this.jdbc = jdbc;
		this.json = json;
		this.conflicts = Counter.builder("payment_conflicting_results_total")
				.description("Payment results discarded because the order was already final with a different outcome")
				.register(meters);
	}

	@RabbitListener(queues = PAYMENT_RESULT)
	void onPaymentResult(Message message) {
		var correlationId = message.getMessageProperties().<String>getHeader(CORRELATION_ID_HEADER);
		if (correlationId != null) {
			MDC.put("correlationId", correlationId);
		}
		try {
			apply(message);
		}
		finally {
			MDC.remove("correlationId");
		}
	}

	private void apply(Message message) {
		long orderId;
		String result;
		try {
			var tree = json.readTree(message.getBody());
			orderId = tree.required("orderId").asLong();
			result = tree.required("result").asString();
		}
		catch (JacksonException | IllegalArgumentException e) {
			throw new AmqpRejectAndDontRequeueException("Invalid payment.result message", e);
		}
		var applied = switch (result) {
			case "APPROVED" -> transitions.confirm(orderId);
			case "DECLINED" -> transitions.cancel(orderId, "PAYMENT_DECLINED", "DECLINED");
			case "EXHAUSTED" -> transitions.cancel(orderId, "PAYMENT_UNAVAILABLE", "EXHAUSTED");
			default -> throw new AmqpRejectAndDontRequeueException("Unknown payment result: " + result);
		};
		if (applied) {
			log.info("Payment result {} applied to order {}", result, orderId);
		}
		else {
			discard(orderId, result);
		}
	}

	/**
	 * First result wins. A repeat of the result that already produced the final state is just a duplicate; a result
	 * that contradicts it is logged and counted, and the order stays as it is.
	 */
	private void discard(long orderId, String result) {
		var status = jdbc.sql("select status from orders where id = :id").param("id", orderId)
				.query(String.class).optional().orElse("MISSING");
		var consistent = status.equals(result.equals("APPROVED") ? "CONFIRMED" : "CANCELLED");
		if (consistent) {
			log.info("Duplicate payment result {} for order {} ignored", result, orderId);
			return;
		}
		log.warn("Conflicting payment result {} for order {} discarded: order is already {}", result, orderId, status);
		conflicts.increment();
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
