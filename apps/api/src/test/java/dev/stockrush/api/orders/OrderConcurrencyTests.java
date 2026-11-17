package dev.stockrush.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The no-overselling proof: 200 concurrent buyers, 50 units, exactly 50 orders. See ADR 0005. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderConcurrencyTests {

	private static final int BUYERS = 200;

	private static final int STOCK = 50;

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void twoHundredBuyersFightingForFiftyUnitsSellExactlyFifty() throws Exception {
		var productId = jdbc.queryForObject("insert into products (name, available_quantity) values (?, ?) returning id",
				Long.class, "race-" + UUID.randomUUID(), STOCK);
		var token = newUserToken();
		var start = new CountDownLatch(1);
		var statuses = new ArrayList<java.util.concurrent.Future<Integer>>();

		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var i = 0; i < BUYERS; i++) {
				statuses.add(pool.submit(() -> {
					start.await();
					return postOrder(token, productId).statusCode();
				}));
			}
			start.countDown();
		}

		var created = 0;
		var rejected = 0;
		for (var status : statuses) {
			switch (status.get()) {
				case 201 -> created++;
				case 409 -> rejected++;
				default -> throw new AssertionError("Unexpected status " + status.get());
			}
		}
		assertThat(created).isEqualTo(STOCK);
		assertThat(rejected).isEqualTo(BUYERS - STOCK);
		assertThat(jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId))
				.isZero();
		assertThat(jdbc.queryForObject("select count(*) from orders where product_id = ?", Integer.class, productId))
				.isEqualTo(STOCK);
	}

	private String newUserToken() throws Exception {
		var credentials = Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery");
		post("/auth/register", null, null, credentials);
		return json.readTree(post("/auth/login", null, null, credentials).body()).path("token").asString();
	}

	private HttpResponse<String> postOrder(String token, long productId) throws Exception {
		return post("/orders", token, UUID.randomUUID().toString(), Map.of("productId", productId, "quantity", 1));
	}

	private HttpResponse<String> post(String path, String token, String key, Map<String, ?> body) throws Exception {
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
