package dev.stockrush.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderQueryTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void listReturnsOnlyTheCallersOrders() throws Exception {
		var productId = newProduct();
		var alice = newUserToken();
		var bob = newUserToken();
		var aliceOrder = createOrder(alice, productId);
		var bobOrder = createOrder(bob, productId);

		var list = json.readTree(get("/orders", alice).body());

		assertThat(list.size()).isEqualTo(1);
		assertThat(list.get(0).path("id").asLong()).isEqualTo(aliceOrder);
		assertThat(list.get(0).path("status").asString()).isEqualTo("PENDING");
		assertThat(bobOrder).isNotEqualTo(aliceOrder);
	}

	@Test
	void anotherUsersOrderIs404AndOwnOrderIs200() throws Exception {
		var productId = newProduct();
		var alice = newUserToken();
		var orderId = createOrder(alice, productId);

		assertThat(get("/orders/" + orderId, alice).statusCode()).isEqualTo(200);
		var foreign = get("/orders/" + orderId, newUserToken());
		assertThat(foreign.statusCode()).isEqualTo(404);
		assertThat(foreign.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
	}

	@Test
	void cancelledOrderExposesOnlyTheCustomerFacingReason() throws Exception {
		var productId = newProduct();
		var token = newUserToken();
		var orderId = createOrder(token, productId);
		jdbc.update("update orders set status = 'CANCELLED', cancellation_reason = 'PAYMENT_UNAVAILABLE', "
				+ "internal_cancellation_reason = 'EXHAUSTED' where id = ?", orderId);

		var response = get("/orders/" + orderId, token);

		var order = json.readTree(response.body());
		assertThat(order.path("status").asString()).isEqualTo("CANCELLED");
		assertThat(order.path("cancellationReason").asString()).isEqualTo("PAYMENT_UNAVAILABLE");
		assertThat(response.body()).doesNotContain("EXHAUSTED").doesNotContain("nternal");
	}

	@Test
	void requiresAuthentication() throws Exception {
		assertThat(get("/orders", null).statusCode()).isEqualTo(401);
	}

	private long newProduct() {
		return jdbc.queryForObject("insert into products (name, available_quantity) values (?, 10) returning id",
				Long.class, "query-" + UUID.randomUUID());
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
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
