package dev.stockrush.api.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.stockrush.api.TestcontainersConfiguration;
import dev.stockrush.api.outbox.OutboxPublisher;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real payment-service jar as a child process against the same RabbitMQ and a separate database in the
 * same Postgres, then walks an order through HTTP, outbox, both services and back. Build the jar first with
 * {@code ../payment-service/mvnw package -DskipTests}; without it the test is skipped.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TwoServiceFlowTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	RabbitMQContainer rabbit;

	@Autowired
	OutboxPublisher publisher;

	private Process paymentService;

	private File paymentLog;

	private String correlationId;

	@BeforeEach
	void startPaymentService() throws Exception {
		var jar = findJar();
		assumeTrue(jar != null, "payment-service jar not built");
		var exists = jdbc.queryForObject("select exists (select 1 from pg_database where datname = 'payments')",
				Boolean.class);
		if (!exists) {
			jdbc.execute("create database payments");
		}
		paymentLog = Files.createTempFile("payment-service", ".log").toFile();
		var builder = new ProcessBuilder("java", "-jar", jar.getAbsolutePath()).redirectErrorStream(true)
				.redirectOutput(paymentLog);
		builder.environment().putAll(Map.of(
				"PORT", "0",
				"JWT_SECRET", "e2e-secret-e2e-secret-e2e-secret-0123456789",
				"DB_URL", "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/payments",
				"DB_USER", postgres.getUsername(),
				"DB_PASSWORD", postgres.getPassword(),
				"RABBITMQ_HOST", rabbit.getHost(),
				"RABBITMQ_PORT", String.valueOf(rabbit.getAmqpPort()),
				"RABBITMQ_USER", rabbit.getAdminUsername(),
				"RABBITMQ_PASSWORD", rabbit.getAdminPassword()));
		paymentService = builder.start();
	}

	@AfterEach
	void stopPaymentService() throws InterruptedException {
		if (paymentService != null) {
			paymentService.destroy();
			paymentService.waitFor(15, TimeUnit.SECONDS);
		}
	}

	@Test
	void approvedOrderIsConfirmedByThePaymentService() throws Exception {
		var productId = newProduct();

		assertThat(placeAndWait(productId, "tok_approve")).isEqualTo("CONFIRMED");
		assertThat(stock(productId)).isEqualTo(3);
	}

	@Test
	void declinedOrderIsCancelledAndItsStockReturned() throws Exception {
		var productId = newProduct();

		assertThat(placeAndWait(productId, "tok_decline")).isEqualTo("CANCELLED");
		assertThat(stock(productId)).isEqualTo(5);
	}

	@Test
	void exhaustedPaymentCancelsTheOrderAndReturnsTheStockOnce() throws Exception {
		var productId = newProduct();

		assertThat(placeAndWait(productId, "tok_dead")).isEqualTo("CANCELLED");

		assertThat(stock(productId)).isEqualTo(5);
		assertThat(jdbc.queryForObject("select cancellation_reason from orders where product_id = ?", String.class,
				productId)).isEqualTo("PAYMENT_UNAVAILABLE");
	}

	@Test
	void theSameCorrelationIdAppearsInTheLogsOfBothServices(CapturedOutput apiLog) throws Exception {
		correlationId = "e2e-" + UUID.randomUUID();
		var productId = newProduct();

		assertThat(placeAndWait(productId, "tok_approve")).isEqualTo("CONFIRMED");

		var marker = "[" + correlationId + "]";
		assertThat(apiLog.getOut()).contains(marker + " ").contains("created for product " + productId)
				.contains("Payment result APPROVED applied");
		assertThat(Files.readString(paymentLog.toPath())).contains(marker).contains("decided on attempt 1: APPROVED");
	}

	private long newProduct() {
		return jdbc.queryForObject("insert into products (name, available_quantity) values (?, 5) returning id",
				Long.class, "e2e-" + UUID.randomUUID());
	}

	private int stock(long productId) {
		return jdbc.queryForObject("select available_quantity from products where id = ?", Integer.class, productId);
	}

	private String placeAndWait(long productId, String paymentToken) throws Exception {
		var credentials = Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery");
		post("/auth/register", null, null, credentials);
		var token = json.readTree(post("/auth/login", null, null, credentials).body()).path("token").asString();
		var key = UUID.randomUUID().toString();
		var body = Map.of("productId", productId, "quantity", 2, "paymentToken", paymentToken);

		var orderId = json.readTree(post("/orders", token, key, body).body()).path("id").asLong();
		post("/orders", token, key, body);

		var deadline = System.nanoTime() + 60_000_000_000L;
		String status;
		do {
			publisher.publishPending();
			Thread.sleep(250);
			status = jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
		}
		while (status.equals("PENDING") && System.nanoTime() < deadline);

		return status;
	}

	private File findJar() {
		var target = Path.of("..", "payment-service", "target").toFile();
		var jars = target.listFiles((dir, name) -> name.startsWith("payment-service-") && name.endsWith(".jar")
				&& !name.endsWith("-plain.jar"));
		return jars == null || jars.length == 0 ? null : jars[0];
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
		if (correlationId != null) {
			builder.header("X-Correlation-Id", correlationId);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

}
