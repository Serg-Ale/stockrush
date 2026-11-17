package dev.stockrush.api.orders;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@AutoConfigureMetrics // @SpringBootTest turns metric exporters off, and the scrape endpoint with them
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderMetricsTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RabbitTemplate rabbit;

	@Autowired
	MeterRegistry meters;

	@Test
	void scrapeExposesBusinessMetricsWithoutAuthentication() throws Exception {
		var response = scrape();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("orders{status=\"PENDING\"", "orders{status=\"CONFIRMED\"",
				"orders{status=\"CANCELLED\"", "stock_available{product=", "payment_conflicting_results_total",
				"orders_cancelled_total{reason=\"DECLINED\"", "orders_cancelled_total{reason=\"EXHAUSTED\"",
				"orders_cancelled_total{reason=\"INVALID_MESSAGE\"", "orders_cancelled_total{reason=\"TIMEOUT\"");
	}

	@Test
	void ordersGaugeFollowsTheDatabaseAndCancellationsAreCountedByInternalReason() throws Exception {
		var pendingBefore = gauge("orders", "status", "PENDING");
		var declinedBefore = cancelled("DECLINED");
		var orderId = newPendingOrder();
		assertThat(gauge("orders", "status", "PENDING")).isEqualTo(pendingBefore + 1);

		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		var body = json.writeValueAsBytes(Map.of("orderId", orderId, "result", "DECLINED"));
		rabbit.send("payments", "payment.result", MessageBuilder.withBody(body).andProperties(properties).build());

		PaymentResultTests.await(() -> cancelled("DECLINED") == declinedBefore + 1);
		assertThat(gauge("orders", "status", "PENDING")).isEqualTo(pendingBefore);
	}

	private HttpResponse<String> scrape() throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus")).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private double gauge(String name, String tag, String value) {
		return meters.get(name).tag(tag, value).gauge().value();
	}

	private double cancelled(String reason) {
		return meters.get("orders_cancelled_total").tag("reason", reason).counter().count();
	}

	private long newPendingOrder() {
		var userId = jdbc.queryForObject("insert into users (email, password_hash) values (?, 'x') returning id",
				Long.class, "user-" + UUID.randomUUID() + "@example.com");
		var productId = jdbc.queryForObject("insert into products (name, available_quantity) values (?, 4) returning id",
				Long.class, "test-" + UUID.randomUUID());
		return jdbc.queryForObject(
				"insert into orders (user_id, product_id, quantity, status) values (?, ?, 1, 'PENDING') returning id",
				Long.class, userId, productId);
	}

}
