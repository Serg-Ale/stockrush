package dev.stockrush.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Drives the service only through the queues: publishes order.created, observes payment.result. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentResultTests {

	private final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	RabbitTemplate rabbit;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RabbitAdmin admin;

	@Autowired
	MeterRegistry meters;

	@Test
	void approveTokenPublishesApproved() throws Exception {
		var orderId = newOrderId();

		orderCreated(orderId, "tok_approve");

		assertThat(resultFor(orderId)).isEqualTo("APPROVED");
	}

	@Test
	void declineTokenPublishesDeclined() throws Exception {
		var orderId = newOrderId();

		orderCreated(orderId, "tok_decline");

		assertThat(resultFor(orderId)).isEqualTo("DECLINED");
	}

	@Test
	void redeliveredEventGetsTheSameStoredDecision() throws Exception {
		var orderId = newOrderId();
		orderCreated(orderId, "tok_decline");
		assertThat(resultFor(orderId)).isEqualTo("DECLINED");

		orderCreated(orderId, "tok_approve");

		assertThat(resultFor(orderId)).isEqualTo("DECLINED");
		assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId))
				.isEqualTo(1);
	}

	@Test
	void unsupportedTokenMissingTokenAndMalformedMessageProduceNoResult() throws Exception {
		var unsupported = newOrderId();
		orderCreated(unsupported, "tok_nope");
		var missingToken = newOrderId();
		send(Map.of("orderId", missingToken));
		rabbit.send("orders", "order.created", raw("not json".getBytes()));
		var marker = newOrderId();
		orderCreated(marker, "tok_approve");

		assertThat(resultFor(marker)).isEqualTo("APPROVED");
		assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, unsupported))
				.isZero();
		assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, missingToken))
				.isZero();
	}

	@Test
	void flakyTokenIsApprovedOnTheThirdAttemptAfterTheConfiguredBackoff() throws Exception {
		var orderId = newOrderId();
		var start = System.nanoTime();

		orderCreated(orderId, "tok_flaky");

		assertThat(resultFor(orderId)).isEqualTo("APPROVED");
		var elapsedMs = (System.nanoTime() - start) / 1_000_000;
		// two failures wait 0.3 s and 0.6 s before the approved third attempt
		assertThat(elapsedMs).isBetween(900L, 4_000L);
	}

	@Test
	void aRetryingMessageDoesNotBlockOtherOrders() throws Exception {
		var flaky = newOrderId();
		var quick = newOrderId();
		orderCreated(flaky, "tok_flaky");
		orderCreated(quick, "tok_approve");
		var start = System.nanoTime();

		assertThat(resultFor(quick)).isEqualTo("APPROVED");

		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(900L);
		assertThat(resultFor(flaky)).isEqualTo("APPROVED");
	}

	@Test
	void deadTokenExhaustsAttemptsPublishesExhaustedAndKeepsTheMessageInTheDlq() throws Exception {
		admin.purgeQueue("order.created.dlq", true);
		var orderId = newOrderId();

		orderCreated(orderId, "tok_dead");

		assertThat(resultFor(orderId)).isEqualTo("EXHAUSTED");
		var parked = rabbit.receive("order.created.dlq", 5_000);
		assertThat(parked).isNotNull();
		assertThat(json.readTree(parked.getBody()).path("orderId").asLong()).isEqualTo(orderId);
		assertThat(jdbc.queryForObject("select count(*) from payments where order_id = ?", Integer.class, orderId))
				.isZero();
	}

	@Test
	void deadLetterDepthIsExportedAsAMetric() throws Exception {
		admin.purgeQueue("order.created.dlq", true);
		var orderId = newOrderId();
		orderCreated(orderId, "tok_dead");
		assertThat(resultFor(orderId)).isEqualTo("EXHAUSTED");

		var depth = meters.get("payment_dlq_depth").gauge();

		await(() -> depth.value() == 1.0);
	}

	@Test
	void deadLetterQueueKeepsAtMost1000Messages() throws Exception {
		admin.purgeQueue("order.created.dlq", true);
		for (var i = 0; i < 1_050; i++) {
			rabbit.send("", "order.created.dlq", raw("{}".getBytes()));
		}

		await(() -> admin.getQueueInfo("order.created.dlq").getMessageCount() >= 1_000);
		Thread.sleep(500);
		assertThat(admin.getQueueInfo("order.created.dlq").getMessageCount()).isEqualTo(1_000);
		admin.purgeQueue("order.created.dlq", true);
	}

	private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
		var deadline = System.nanoTime() + 10_000_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Condition not met within 10 s");
			}
			Thread.sleep(50);
		}
	}

	private void orderCreated(long orderId, String token) throws Exception {
		send(Map.of("orderId", orderId, "productId", 1, "quantity", 1, "paymentToken", token));
	}

	private void send(Map<String, ?> payload) throws Exception {
		rabbit.send("orders", "order.created", raw(json.writeValueAsBytes(payload)));
	}

	private Message raw(byte[] body) {
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		return MessageBuilder.withBody(body).andProperties(properties).build();
	}

	/** Reads payment.result until the message for this order shows up; the queue is shared by all tests. */
	private String resultFor(long orderId) throws Exception {
		var deadline = System.nanoTime() + 10_000_000_000L;
		while (System.nanoTime() < deadline) {
			var message = rabbit.receive("payment.result", 500);
			if (message != null) {
				var tree = json.readTree(message.getBody());
				if (tree.path("orderId").asLong() == orderId) {
					return tree.path("result").asString();
				}
			}
		}
		return null;
	}

	private long newOrderId() {
		return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
	}

}
