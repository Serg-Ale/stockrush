package dev.stockrush.api.orders;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Pushes order status changes to the owner's open SSE streams. The registry lives in memory, which is enough
 * because v1 runs a single api instance (ADR 0006).
 */
@Component
class OrderEvents {

	private static final Logger log = LoggerFactory.getLogger(OrderEvents.class);

	private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

	private final Map<String, Set<SseEmitter>> streams = new ConcurrentHashMap<>();

	private final JdbcClient jdbc;

	OrderEvents(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Ends every open stream on shutdown, so the server does not wait for clients to disconnect. */
	@PreDestroy
	void closeAll() {
		streams.values().forEach(emitters -> emitters.forEach(SseEmitter::complete));
		streams.clear();
	}

	SseEmitter subscribe(String userEmail) {
		var emitter = new SseEmitter(TIMEOUT_MS);
		var userStreams = streams.computeIfAbsent(userEmail, k -> new CopyOnWriteArraySet<>());
		userStreams.add(emitter);
		Runnable remove = () -> userStreams.remove(emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(e -> remove.run());
		try {
			emitter.send(SseEmitter.event().comment("connected"));
		}
		catch (IOException e) {
			remove.run();
		}
		return emitter;
	}

	/** Called after a transition committed: sends the order's new state to its owner's streams, if any. */
	void statusChanged(long orderId) {
		var change = jdbc.sql("""
				select u.email, o.id, o.status, o.cancellation_reason from orders o
				join users u on u.id = o.user_id where o.id = :id
				""")
				.param("id", orderId)
				.query((rs, i) -> Map.entry(rs.getString("email"),
						new OrderService.OrderStatus(rs.getLong("id"), rs.getString("status"),
								rs.getString("cancellation_reason"))))
				.optional();
		if (change.isEmpty()) {
			return;
		}
		for (var emitter : streams.getOrDefault(change.get().getKey(), Set.of())) {
			try {
				emitter.send(SseEmitter.event().name("order-status").data(change.get().getValue()));
			}
			catch (IOException | IllegalStateException e) {
				log.debug("Dropping a closed order stream");
				emitter.completeWithError(e);
			}
		}
	}

}
