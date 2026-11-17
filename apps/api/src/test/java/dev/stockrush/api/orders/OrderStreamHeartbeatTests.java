package dev.stockrush.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.sse.heartbeat=PT0.2S")
class OrderStreamHeartbeatTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Test
	void anIdleStreamKeepsReceivingKeepaliveComments() throws Exception {
		var body = json.writeValueAsString(
				Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery"));
		post("/auth/register", body);
		var token = json.readTree(post("/auth/login", body).body()).path("token").asString();
		var lines = new LinkedBlockingQueue<String>();
		var stream = http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/stream"))
				.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofLines())
				.thenAccept(response -> response.body().filter(line -> !line.isEmpty()).forEach(lines::add));

		assertThat(lines.poll(10, TimeUnit.SECONDS)).isEqualTo(":connected");
		assertThat(lines.poll(5, TimeUnit.SECONDS)).isEqualTo(":keepalive");
		assertThat(lines.poll(5, TimeUnit.SECONDS)).as("the second one is not the last").isNotNull();
		stream.cancel(true);
		http.shutdownNow();
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

}
