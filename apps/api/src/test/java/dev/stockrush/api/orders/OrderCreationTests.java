package dev.stockrush.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderCreationTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void createsPendingOrderAndDecrementsStock() throws Exception {
		var productId = newProduct(10);
		var token = newUserToken();

		var response = postOrder(token, productId, 3);

		assertThat(response.statusCode()).isEqualTo(201);
		var body = json.readTree(response.body());
		assertThat(body.path("status").asString()).isEqualTo("PENDING");
		assertThat(body.path("productId").asLong()).isEqualTo(productId);
		assertThat(body.path("quantity").asInt()).isEqualTo(3);
		assertThat(stock(productId)).isEqualTo(7);
		assertThat(jdbc.queryForObject("select count(*) from orders where product_id = ?", Integer.class, productId))
				.isEqualTo(1);
	}

	@Test
	void insufficientStockReturns409ProblemDetailsAndLeavesStockUntouched() throws Exception {
		var productId = newProduct(2);
		var token = newUserToken();

		var response = postOrder(token, productId, 3);

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(json.readTree(response.body()).path("status").asInt()).isEqualTo(409);
		assertThat(stock(productId)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from orders where product_id = ?", Integer.class, productId))
				.isZero();
	}

	@Test
	void exactRemainingStockCanBeBoughtThenNothingIsLeft() throws Exception {
		var productId = newProduct(2);
		var token = newUserToken();

		assertThat(postOrder(token, productId, 2).statusCode()).isEqualTo(201);
		assertThat(postOrder(token, productId, 1).statusCode()).isEqualTo(409);
		assertThat(stock(productId)).isZero();
	}

	@Test
	void unknownProductReturns404() throws Exception {
		assertThat(postOrder(newUserToken(), Long.MAX_VALUE, 1).statusCode()).isEqualTo(404);
	}

	@Test
	void invalidQuantityReturns400() throws Exception {
		var productId = newProduct(5);

		var response = postOrder(newUserToken(), productId, 0);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(stock(productId)).isEqualTo(5);
	}

	@Test
	void requiresAuthentication() throws Exception {
		assertThat(postOrder(null, newProduct(5), 1).statusCode()).isEqualTo(401);
	}

	@Test
	void replayWithSameKeyReturnsSameOrderAndReservesStockOnce() throws Exception {
		var productId = newProduct(10);
		var token = newUserToken();
		var key = UUID.randomUUID().toString();

		var first = postOrder(token, key, productId, 3);
		var second = postOrder(token, key, productId, 3);

		assertThat(first.statusCode()).isEqualTo(201);
		assertThat(second.statusCode()).isEqualTo(201);
		assertThat(json.readTree(second.body()).path("id").asLong())
				.isEqualTo(json.readTree(first.body()).path("id").asLong());
		assertThat(stock(productId)).isEqualTo(7);
		assertThat(jdbc.queryForObject("select count(*) from orders where product_id = ?", Integer.class, productId))
				.isEqualTo(1);
	}

	@Test
	void concurrentRequestsWithSameKeyCreateOneOrder() throws Exception {
		var productId = newProduct(10);
		var token = newUserToken();
		var key = UUID.randomUUID().toString();

		try (var pool = Executors.newFixedThreadPool(8)) {
			var calls = IntStream.range(0, 8)
					.mapToObj(i -> pool.submit(() -> postOrder(token, key, productId, 2)))
					.toList();
			for (var call : calls) {
				assertThat(call.get().statusCode()).isEqualTo(201);
			}
		}

		assertThat(stock(productId)).isEqualTo(8);
		assertThat(jdbc.queryForObject("select count(*) from orders where product_id = ?", Integer.class, productId))
				.isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentBodyReturns422ProblemDetails() throws Exception {
		var productId = newProduct(10);
		var token = newUserToken();
		var key = UUID.randomUUID().toString();
		postOrder(token, key, productId, 3);

		var response = postOrder(token, key, productId, 4);

		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(stock(productId)).isEqualTo(7);
	}

	@Test
	void sameKeyFromAnotherUserIsIndependent() throws Exception {
		var productId = newProduct(10);
		var key = UUID.randomUUID().toString();

		assertThat(postOrder(newUserToken(), key, productId, 1).statusCode()).isEqualTo(201);
		assertThat(postOrder(newUserToken(), key, productId, 1).statusCode()).isEqualTo(201);
		assertThat(stock(productId)).isEqualTo(8);
	}

	@Test
	void missingKeyReturns400() throws Exception {
		var productId = newProduct(5);

		var response = send("/orders", newUserToken(), null, Map.of("productId", productId, "quantity", 1));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(stock(productId)).isEqualTo(5);
	}

	@Test
	void rejectedOrderDoesNotConsumeTheKey() throws Exception {
		var productId = newProduct(1);
		var token = newUserToken();
		var key = UUID.randomUUID().toString();

		assertThat(postOrder(token, key, productId, 2).statusCode()).isEqualTo(409);
		assertThat(postOrder(token, key, productId, 1).statusCode()).isEqualTo(201);
	}

	@Test
	void createdOrderLeavesOnePendingOutboxEvent() throws Exception {
		var productId = newProduct(10);
		var token = newUserToken();
		var key = UUID.randomUUID().toString();

		var orderId = json.readTree(postOrder(token, key, productId, 3).body()).path("id").asLong();
		postOrder(token, key, productId, 3);

		var events = jdbc.queryForList(
				"select type, payload::text as payload from outbox where aggregate_id = ? and published_at is null",
				orderId);
		assertThat(events).hasSize(1);
		assertThat(events.get(0).get("type")).isEqualTo("order.created");
		var payload = json.readTree((String) events.get(0).get("payload"));
		assertThat(payload.path("orderId").asLong()).isEqualTo(orderId);
		assertThat(payload.path("productId").asLong()).isEqualTo(productId);
		assertThat(payload.path("quantity").asInt()).isEqualTo(3);
	}

	@Test
	void rejectedOrderLeavesNoOutboxEvent() throws Exception {
		var productId = newProduct(1);
		var before = jdbc.queryForObject("select count(*) from outbox", Integer.class);

		assertThat(postOrder(newUserToken(), productId, 2).statusCode()).isEqualTo(409);

		assertThat(jdbc.queryForObject("select count(*) from outbox", Integer.class)).isEqualTo(before);
	}

	private long newProduct(int quantity) {
		return jdbc.queryForObject(
				"insert into products (name, available_quantity) values (?, ?) returning id", Long.class,
				"test-" + UUID.randomUUID(), quantity);
	}

	private int stock(long productId) {
		return jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId);
	}

	private String newUserToken() throws Exception {
		var credentials = Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery");
		send("/auth/register", null, null, credentials);
		var login = send("/auth/login", null, null, credentials);
		return json.readTree(login.body()).path("token").asString();
	}

	private HttpResponse<String> postOrder(String token, long productId, int quantity) throws Exception {
		return postOrder(token, UUID.randomUUID().toString(), productId, quantity);
	}

	private HttpResponse<String> postOrder(String token, String key, long productId, int quantity) throws Exception {
		return send("/orders", token, key, Map.of("productId", productId, "quantity", quantity));
	}

	private HttpResponse<String> send(String path, String token, String key, Map<String, ?> body) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		if (key != null) {
			builder.header("Idempotency-Key", key);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
