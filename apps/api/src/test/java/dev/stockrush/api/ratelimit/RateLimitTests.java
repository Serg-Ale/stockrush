package dev.stockrush.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.rate-limit.requests=5", "app.rate-limit.window=PT30S" })
class RateLimitTests {

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Test
	void requestsOverTheLimitGet429ProblemDetailsWithRetryAfter() throws Exception {
		for (var i = 0; i < 5; i++) {
			assertThat(get("/products").statusCode()).isEqualTo(200);
		}

		var limited = get("/products");

		assertThat(limited.statusCode()).isEqualTo(429);
		assertThat(limited.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
		assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 30L);
		assertThat(JsonMapper.builder().build().readTree(limited.body()).path("status").asInt()).isEqualTo(429);
	}

	@Test
	void healthCheckIsNeverLimited() throws Exception {
		for (var i = 0; i < 15; i++) {
			assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
		}
	}

	private HttpResponse<String> get(String path) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

}
