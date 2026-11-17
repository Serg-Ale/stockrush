package dev.stockrush.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Opens real SSE streams and drives status changes with payment.result messages, as payment-service would. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderStreamTests {

	private HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RabbitTemplate rabbit;

	@BeforeEach
	void newClient() {
		http = HttpClient.newHttpClient();
	}

	private final java.util.List<java.util.stream.Stream<String>> open = new java.util.concurrent.CopyOnWriteArrayList<>();

	@AfterEach
	void closeStreams() {
		open.forEach(java.util.stream.Stream::close);
		open.clear();
		http.shutdownNow();
	}

	@Test
	void statusChangeReachesTheConnectedOwnerAndNobodyElse() throws Exception {
		var alice = newUserToken();
		var bob = newUserToken();
		var aliceLines = openStream(alice);
		var bobLines = openStream(bob);
		var aliceOrder = createOrder(alice);
		var bobOrder = createOrder(bob);

		result(aliceOrder, "APPROVED");
		var event = nextData(aliceLines);

		assertThat(event.path("orderId").asLong()).isEqualTo(aliceOrder);
		assertThat(event.path("status").asString()).isEqualTo("CONFIRMED");
		result(bobOrder, "APPROVED");
		assertThat(nextData(bobLines).path("orderId").asLong()).isEqualTo(bobOrder);
		assertThat(aliceLines.poll(500, TimeUnit.MILLISECONDS)).isNull();
		assertThat(bobLines.poll(500, TimeUnit.MILLISECONDS)).isNull();
	}

	@Test
	void cancellationEventCarriesTheCustomerFacingReasonOnly() throws Exception {
		var token = newUserToken();
		var lines = openStream(token);
		var orderId = createOrder(token);

		result(orderId, "EXHAUSTED");

		var raw = nextEventBlock(lines);
		var event = json.readTree(raw.substring(raw.indexOf("data:") + 5).strip());
		assertThat(raw).contains("event:order-status");
		assertThat(event.path("status").asString()).isEqualTo("CANCELLED");
		assertThat(event.path("cancellationReason").asString()).isEqualTo("PAYMENT_UNAVAILABLE");
		assertThat(raw).doesNotContain("EXHAUSTED");
	}

	@Test
	void streamRequiresAuthentication() throws Exception {
		var response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/stream")).build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(401);
	}

	/** Returns the stream's lines; blocks until the server's "connected" comment shows the stream is live. */
	private BlockingQueue<String> openStream(String token) throws Exception {
		var lines = new LinkedBlockingQueue<String>();
		http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders/stream"))
				.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofLines())
				.thenAccept(response -> {
					open.add(response.body());
					response.body().forEach(lines::add);
				});
		assertThat(lines.poll(10, TimeUnit.SECONDS)).startsWith(":");
		return lines;
	}


	private tools.jackson.databind.JsonNode nextData(BlockingQueue<String> lines) throws Exception {
		var block = nextEventBlock(lines);
		return json.readTree(block.substring(block.indexOf("data:") + 5).strip());
	}

	/** Collects lines up to the blank line that ends one SSE event. */
	private String nextEventBlock(BlockingQueue<String> lines) throws Exception {
		var block = new StringBuilder();
		while (true) {
			var line = lines.poll(10, TimeUnit.SECONDS);
			assertThat(line).as("an SSE line within 10 s").isNotNull();
			if (line.isEmpty()) {
				if (block.length() > 0) {
					return block.toString();
				}
				continue;
			}
			block.append(line).append('\n');
		}
	}

	private long createOrder(String token) throws Exception {
		var productId = jdbc.queryForObject("insert into products (name, available_quantity) values (?, 10) returning id",
				Long.class, "stream-" + UUID.randomUUID());
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/orders"))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.POST(HttpRequest.BodyPublishers.ofString(
						json.writeValueAsString(Map.of("productId", productId, "quantity", 1))))
				.build();
		return json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).path("id").asLong();
	}

	private void result(long orderId, String result) throws Exception {
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		var body = json.writeValueAsBytes(Map.of("orderId", orderId, "result", result));
		rabbit.send("payments", "payment.result", MessageBuilder.withBody(body).andProperties(properties).build());
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

}
