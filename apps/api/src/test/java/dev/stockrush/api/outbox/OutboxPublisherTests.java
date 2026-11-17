package dev.stockrush.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxPublisherTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RabbitTemplate rabbit;

	@Autowired
	OutboxPublisher publisher;

	@Test
	void orderCreatedEventReachesTheQueueAndIsMarkedPublished() throws Exception {
		var productId = jdbc.queryForObject("insert into products (name, available_quantity) values (?, 5) returning id",
				Long.class, "test-" + UUID.randomUUID());
		var credentials = Map.of("email", "user-" + UUID.randomUUID() + "@example.com", "password", "correct-horse-battery");
		post("/auth/register", null, null, credentials);
		var token = json.readTree(post("/auth/login", null, null, credentials).body()).path("token").asString();
		var created = post("/orders", token, UUID.randomUUID().toString(), Map.of("productId", productId, "quantity", 2));
		var orderId = json.readTree(created.body()).path("id").asLong();

		while (publisher.publishPending() > 0) {
			// drain everything left by other tests so the batch holding this order is surely sent
		}

		var payloads = drainQueue();
		assertThat(payloads).anySatisfy(p -> {
			assertThat(p.path("orderId").asLong()).isEqualTo(orderId);
			assertThat(p.path("productId").asLong()).isEqualTo(productId);
			assertThat(p.path("quantity").asInt()).isEqualTo(2);
		});
		assertThat(jdbc.queryForObject("select published_at is not null from outbox where aggregate_id = ?",
				Boolean.class, orderId)).isTrue();
	}

	@Test
	void concurrentPublishersDoNotDuplicateEvents() throws Exception {
		var base = jdbc.queryForObject("select coalesce(max(aggregate_id), 0) + 1000000 from outbox", Long.class);
		var total = 120;
		for (var i = 0; i < total; i++) {
			jdbc.update("insert into outbox (aggregate_id, type, payload) values (?, 'order.created', "
					+ "jsonb_build_object('orderId', ?::bigint))", base + i, base + i);
		}

		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			List<Callable<Integer>> publishers = List.of(() -> publishAll(start), () -> publishAll(start));
			var futures = publishers.stream().map(pool::submit).toList();
			start.countDown();
			for (var future : futures) {
				future.get();
			}
		}

		var mine = drainQueue().stream().map(p -> p.path("orderId").asLong()).filter(id -> id >= base).toList();
		assertThat(mine).hasSize(total).doesNotHaveDuplicates();
		assertThat(jdbc.queryForObject("select count(*) from outbox where aggregate_id >= ? and published_at is null",
				Integer.class, base)).isZero();
	}

	private int publishAll(CountDownLatch start) throws InterruptedException {
		start.await();
		var sent = 0;
		int batch;
		while ((batch = publisher.publishPending()) > 0) {
			sent += batch;
		}
		return sent;
	}

	private List<tools.jackson.databind.JsonNode> drainQueue() throws Exception {
		var payloads = new ArrayList<tools.jackson.databind.JsonNode>();
		org.springframework.amqp.core.Message message;
		while ((message = rabbit.receive(OutboxPublisher.ORDER_CREATED, 500)) != null) {
			payloads.add(json.readTree(message.getBody()));
		}
		return payloads;
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
