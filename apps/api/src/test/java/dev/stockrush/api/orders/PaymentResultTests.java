package dev.stockrush.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Publishes payment.result messages, as payment-service would, and observes the orders table. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaymentResultTests {

	private final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RabbitTemplate rabbit;

	@Test
	void approvedResultConfirmsThePendingOrder() throws Exception {
		var orderId = newOrder("PENDING");

		result(orderId, "APPROVED");

		await(() -> status(orderId).equals("CONFIRMED"));
	}

	@Test
	void duplicatedResultLeavesTheOrderAsItIs() throws Exception {
		var orderId = newOrder("PENDING");
		var productId = jdbc.queryForObject("select product_id from orders where id = ?", Long.class, orderId);
		var stock = jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId);

		result(orderId, "APPROVED");
		result(orderId, "APPROVED");
		var marker = newOrder("PENDING");
		result(marker, "APPROVED");

		await(() -> status(marker).equals("CONFIRMED"));
		assertThat(status(orderId)).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId))
				.isEqualTo(stock);
	}

	@Test
	void approvedResultNeverReopensAFinalOrder() throws Exception {
		var cancelled = newOrder("CANCELLED");
		var marker = newOrder("PENDING");

		result(cancelled, "APPROVED");
		result(marker, "APPROVED");

		await(() -> status(marker).equals("CONFIRMED"));
		assertThat(status(cancelled)).isEqualTo("CANCELLED");
	}

	@Test
	void declinedResultCancelsTheOrderAndReturnsTheStockOnce() throws Exception {
		var orderId = newOrder("PENDING");
		var productId = jdbc.queryForObject("select product_id from orders where id = ?", Long.class, orderId);

		result(orderId, "DECLINED");
		result(orderId, "DECLINED");
		var marker = newOrder("PENDING");
		result(marker, "DECLINED");

		await(() -> status(marker).equals("CANCELLED"));
		assertThat(status(orderId)).isEqualTo("CANCELLED");
		assertThat(jdbc.queryForObject("select cancellation_reason from orders where id = ?", String.class, orderId))
				.isEqualTo("PAYMENT_DECLINED");
		assertThat(jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId))
				.isEqualTo(5);
	}

	@Test
	void declinedResultNeverCancelsAConfirmedOrder() throws Exception {
		var confirmed = newOrder("CONFIRMED");
		var marker = newOrder("PENDING");

		result(confirmed, "DECLINED");
		result(marker, "DECLINED");

		await(() -> status(marker).equals("CANCELLED"));
		assertThat(status(confirmed)).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForObject("select available_quantity from products where id = (select product_id from orders where id = ?)",
				Integer.class, confirmed)).isEqualTo(4);
	}

	private void result(long orderId, String result) throws Exception {
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		var body = json.writeValueAsBytes(Map.of("orderId", orderId, "result", result));
		rabbit.send("payments", "payment.result", MessageBuilder.withBody(body).andProperties(properties).build());
	}

	private String status(long orderId) {
		return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
	}

	static void await(BooleanSupplier condition) throws InterruptedException {
		var deadline = System.nanoTime() + 10_000_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Condition not met within 10 s");
			}
			Thread.sleep(50);
		}
	}

	private long newOrder(String status) {
		var email = "user-" + UUID.randomUUID() + "@example.com";
		var userId = jdbc.queryForObject(
				"insert into users (email, password_hash) values (?, 'x') returning id", Long.class,
				email);
		var productId = jdbc.queryForObject(
				"insert into products (name, available_quantity) values (?, 4) returning id", Long.class,
				"test-" + UUID.randomUUID());
		return jdbc.queryForObject("insert into orders (user_id, product_id, quantity, status) values (?, ?, 1, ?) "
				+ "returning id", Long.class, userId, productId, status);
	}

}
