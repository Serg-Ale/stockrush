package dev.stockrush.api.orders;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Applies the payment service's results to orders. The transition is a conditional UPDATE from PENDING, so a
 * result delivered twice, or one that arrives after the order is already final, changes nothing.
 */
@Component
class PaymentResultListener {

	private static final Logger log = LoggerFactory.getLogger(PaymentResultListener.class);

	static final String PAYMENTS_EXCHANGE = "payments";

	static final String PAYMENT_RESULT = "payment.result";

	private final JdbcClient jdbc;

	private final JsonMapper json;

	PaymentResultListener(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@RabbitListener(queues = PAYMENT_RESULT)
	void onPaymentResult(Message message) {
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
		if (!result.equals("APPROVED")) {
			// DECLINED and EXHAUSTED cancel the order and return the stock: a later ticket.
			log.warn("Ignoring unsupported payment result {} for order {}", result, orderId);
			return;
		}
		var confirmed = jdbc.sql("update orders set status = 'CONFIRMED' where id = :id and status = 'PENDING'")
				.param("id", orderId)
				.update();
		if (confirmed == 0) {
			log.info("Payment result {} for order {} discarded: order is not PENDING", result, orderId);
		}
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
