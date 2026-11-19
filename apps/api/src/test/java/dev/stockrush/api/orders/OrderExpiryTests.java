package dev.stockrush.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * No payment-service runs here and the outbox publisher is off, so an order stays PENDING until the expiry job
 * (limit shortened to 3 s) cancels it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.orders.expiry.timeout=PT3S", "app.orders.expiry.interval=PT0.2S" })
class OrderExpiryTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	OrderExpiry expiry;

	@Autowired
	RabbitTemplate rabbit;

	@Autowired
	MeterRegistry meters;

	@Test
	void orderWithinTheLimitIsLeftAloneAndAnOverdueOneIsExpiredWithItsStockReturnedOnce() throws Exception {
		var productId = newProduct(10);
		var token = newUserToken();
		var orderId = createOrder(token, productId);

		var fresh = order(token, orderId);
		assertThat(fresh.path("status").asString()).isEqualTo("PENDING");
		assertThat(stock(productId)).isEqualTo(9);

		PaymentResultTests.await(() -> isCancelled(token, orderId));

		var expired = order(token, orderId);
		assertThat(expired.path("cancellationReason").asString()).isEqualTo("EXPIRED");
		assertThat(jdbc.queryForObject("select internal_cancellation_reason from orders where id = ?", String.class,
				orderId)).isEqualTo("TIMEOUT");
		assertThat(stock(productId)).isEqualTo(10);
	}

	@Test
	void runningTheJobAgainOrOnTwoInstancesNeverReturnsTheStockTwice() throws Exception {
		var productId = newProduct(10);
		var token = newUserToken();
		var orderId = createOrder(token, productId);
		jdbc.update("update orders set created_at = now() - interval '1 hour' where id = ?", orderId);

		try (var pool = Executors.newFixedThreadPool(4)) {
			List<Future<Integer>> runs = List.of(pool.submit(expiry::expireOverdue), pool.submit(expiry::expireOverdue),
					pool.submit(expiry::expireOverdue), pool.submit(expiry::expireOverdue));
			for (var run : runs) {
				run.get();
			}
		}
		expiry.expireOverdue();

		assertThat(order(token, orderId).path("status").asString()).isEqualTo("CANCELLED");
		assertThat(stock(productId)).isEqualTo(10);
	}

	@Test
	void aLateResultAfterTheExpiryIsDiscardedAndCounted() throws Exception {
		var productId = newProduct(10);
		var token = newUserToken();
		var orderId = createOrder(token, productId);
		jdbc.update("update orders set created_at = now() - interval '1 hour' where id = ?", orderId);
		PaymentResultTests.await(() -> isCancelled(token, orderId));
		var before = conflicts();

		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		var body = json.writeValueAsBytes(Map.of("orderId", orderId, "result", "APPROVED"));
		rabbit.send("payments", "payment.result", MessageBuilder.withBody(body).andProperties(properties).build());

		PaymentResultTests.await(() -> conflicts() == before + 1);
		assertThat(order(token, orderId).path("status").asString()).isEqualTo("CANCELLED");
		assertThat(stock(productId)).isEqualTo(10);
	}

	private boolean isCancelled(String token, long orderId) {
		try {
			return order(token, orderId).path("status").asString().equals("CANCELLED");
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private double conflicts() {
		var counter = meters.find("payment_conflicting_results_total").counter();
		return counter == null ? 0 : counter.count();
	}

	private int stock(long productId) {
		return jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId);
	}

	private long newProduct(int stock) {
		return jdbc.queryForObject("insert into products (name, available_quantity) values (?, ?) returning id",
				Long.class, "expiry-" + UUID.randomUUID(), stock);
	}

	private JsonNode order(String token, long orderId) throws Exception {
		return json.readTree(get("/orders/" + orderId, token).body());
	}

	private long createOrder(String token, long productId) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.POST(HttpRequest.BodyPublishers.ofString(
						json.writeValueAsString(Map.of("productId", productId, "quantity", 1))))
				.build();
		return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).path("id").asLong();
	}

	private String newUserToken() throws Exception {
		var body = json.writeValueAsString(
				Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery"));
		post("/auth/register", body);
		return json.readTree(post("/auth/login", body).body()).path("token").asString();
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
	}

}
