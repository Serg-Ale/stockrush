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

/** Without app.rate-limit.trust-forwarded-for (the default) X-Forwarded-For is client-supplied and must not matter. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.rate-limit.requests=5", "app.rate-limit.window=PT30S" })
class RateLimitIgnoresForwardedForTests {

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Test
	void theHeaderIsIgnoredByDefault() throws Exception {
		for (var i = 0; i < 5; i++) {
			assertThat(get("203.0.113." + i).statusCode()).isEqualTo(200);
		}

		assertThat(get("203.0.113.99").statusCode()).isEqualTo(429);
	}

	private HttpResponse<String> get(String forwardedFor) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/products"))
			.header("X-Forwarded-For", forwardedFor)
			.build(), HttpResponse.BodyHandlers.ofString());
	}

}
