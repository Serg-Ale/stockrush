package dev.stockrush.api.auth;

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
class RolesTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void registrationAlwaysCreatesClienteEvenIfTheBodyAsksForAdmin() throws Exception {
		var email = "user-" + UUID.randomUUID() + "@example.com";

		var response = post("/auth/register", Map.of("email", email, "password", "correct-horse-battery", "role", "ADMIN"));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(jdbc.queryForObject("select role from users where email = ?", String.class, email))
				.isEqualTo("CLIENTE");
	}

	@Test
	void adminOnlyRouteIs403ForClienteAndOkForTheDemoAdmin() throws Exception {
		var email = "user-" + UUID.randomUUID() + "@example.com";
		var credentials = Map.of("email", email, "password", "correct-horse-battery");
		post("/auth/register", credentials);

		var cliente = get("/admin/probe", login(credentials));
		var admin = get("/admin/probe",
				login(Map.of("email", "admin@stockrush.demo", "password", "demo-admin-test-password")));
		var anonymous = get("/admin/probe", null);

		assertThat(cliente.statusCode()).isEqualTo(403);
		assertThat(admin.statusCode()).isEqualTo(200);
		assertThat(anonymous.statusCode()).isEqualTo(401);
	}

	@Test
	void demoAdminIsSeededOnceWithABcryptHash() {
		assertThat(jdbc.queryForObject(
				"select count(*) from users where email = 'admin@stockrush.demo' and role = 'ADMIN' and password_hash like '$2%'",
				Integer.class)).isEqualTo(1);
	}

	private String login(Map<String, String> credentials) throws Exception {
		return json.readTree(post("/auth/login", credentials).body()).path("token").asString();
	}

	private HttpResponse<String> post(String path, Map<String, ?> body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
