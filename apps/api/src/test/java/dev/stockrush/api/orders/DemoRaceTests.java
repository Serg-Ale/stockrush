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
class DemoRaceTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void summaryMatchesTheDatabaseAfterTwoHundredBuyersRaceForFiftyUnits() throws Exception {
		var productId = newProduct(50);

		var response = race(adminToken(), Map.of("productId", productId, "buyers", 200));

		assertThat(response.statusCode()).isEqualTo(200);
		var summary = json.readTree(response.body());
		assertThat(summary.path("sold").asInt()).isEqualTo(50);
		assertThat(summary.path("rejected").asInt()).isEqualTo(150);
		assertThat(summary.path("failed").asInt()).isZero();
		assertThat(summary.path("stockBefore").asInt()).isEqualTo(50);
		assertThat(summary.path("stockAfter").asInt()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from orders where product_id = ?", Integer.class, productId))
				.isEqualTo(summary.path("sold").asInt());
		assertThat(jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId))
				.isEqualTo(summary.path("stockAfter").asInt());
	}

	@Test
	void stockCanBeResetSoTheDemoRepeats() throws Exception {
		var productId = newProduct(0);
		var token = adminToken();

		var first = json.readTree(race(token, Map.of("productId", productId, "buyers", 30, "stock", 10)).body());
		var second = json.readTree(race(token, Map.of("productId", productId, "buyers", 30, "stock", 10)).body());

		assertThat(first.path("sold").asInt()).isEqualTo(10);
		assertThat(second.path("sold").asInt()).isEqualTo(10);
		assertThat(second.path("stockBefore").asInt()).isEqualTo(10);
	}

	@Test
	void onlyAdminCanRunIt() throws Exception {
		var productId = newProduct(5);
		var body = Map.of("productId", productId, "buyers", 3);

		assertThat(race(clienteToken(), body).statusCode()).isEqualTo(403);
		assertThat(race(null, body).statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId))
				.isEqualTo(5);
	}

	@Test
	void refusesMoreBuyersThanTheLimitAndInvalidRequests() throws Exception {
		var productId = newProduct(5);
		var token = adminToken();

		var tooMany = race(token, Map.of("productId", productId, "buyers", 501));
		var none = race(token, Map.of("productId", productId, "buyers", 0));
		var unknown = race(token, Map.of("productId", 999_999_999L, "buyers", 3));

		assertThat(tooMany.statusCode()).isEqualTo(400);
		assertThat(tooMany.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(none.statusCode()).isEqualTo(400);
		assertThat(unknown.statusCode()).isEqualTo(404);
		assertThat(jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId))
				.isEqualTo(5);
	}

	private long newProduct(int stock) {
		return jdbc.queryForObject("insert into products (name, available_quantity) values (?, ?) returning id",
				Long.class, "race-" + UUID.randomUUID(), stock);
	}

	private String adminToken() throws Exception {
		return login(Map.of("email", "admin@stockrush.demo", "password", "demo-admin-test-password"));
	}

	private String clienteToken() throws Exception {
		var credentials = Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery");
		post("/auth/register", null, credentials);
		return login(credentials);
	}

	private String login(Map<String, String> credentials) throws Exception {
		return json.readTree(post("/auth/login", null, credentials).body()).path("token").asString();
	}

	private HttpResponse<String> race(String token, Map<String, ?> body) throws Exception {
		return post("/admin/race", token, body);
	}

	private HttpResponse<String> post(String path, String token, Map<String, ?> body) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
