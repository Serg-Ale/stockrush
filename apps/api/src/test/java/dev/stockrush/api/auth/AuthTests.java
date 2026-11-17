package dev.stockrush.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
class AuthTests {

	private static final String PASSWORD = "correct-horse-battery";

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void registeredUserLogsInAndReachesProtectedRoute() throws Exception {
		var email = newEmail();
		assertThat(register(email, PASSWORD).statusCode()).isEqualTo(201);

		var login = login(email, PASSWORD);

		assertThat(login.statusCode()).isEqualTo(200);
		var token = json.readTree(login.body()).path("token").asString();
		assertThat(token).isNotBlank();
		var me = get("/me", token);
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(json.readTree(me.body()).path("email").asString()).isEqualTo(email);
	}

	@Test
	void passwordIsStoredAsBcryptHash() throws Exception {
		var email = newEmail();
		register(email, PASSWORD);

		var stored = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);

		assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
	}

	@Test
	void duplicateEmailIsRejectedWithProblemDetails() throws Exception {
		var email = newEmail();
		register(email, PASSWORD);

		var response = register(email.toUpperCase(), PASSWORD);

		assertThat(response.statusCode()).isEqualTo(409);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(json.readTree(response.body()).path("status").asInt()).isEqualTo(409);
	}

	@Test
	void invalidRegistrationIsRejectedWithProblemDetails() throws Exception {
		var response = register("not-an-email", "short");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
	}

	@Test
	void wrongPasswordAndUnknownEmailBothReturn401() throws Exception {
		var email = newEmail();
		register(email, PASSWORD);

		assertThat(login(email, "wrong-password-123").statusCode()).isEqualTo(401);
		assertThat(login(newEmail(), PASSWORD).statusCode()).isEqualTo(401);
	}

	@Test
	void protectedRouteRejectsMissingAndInvalidToken() throws Exception {
		assertThat(get("/me", null).statusCode()).isEqualTo(401);
		assertThat(get("/me", "not.a.jwt").statusCode()).isEqualTo(401);
	}

	private static String newEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private HttpResponse<String> register(String email, String password) throws Exception {
		return post("/auth/register", email, password);
	}

	private HttpResponse<String> login(String email, String password) throws Exception {
		return post("/auth/login", email, password);
	}

	private HttpResponse<String> post(String path, String email, String password) throws Exception {
		var body = json.writeValueAsString(java.util.Map.of("email", email, "password", password));
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
