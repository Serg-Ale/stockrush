package dev.stockrush.api.orders;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety net for an order stuck in PENDING (payment-service down, outbox stuck, broker gone): cancels the ones
 * older than the limit as {@code EXPIRED} (internal reason {@code TIMEOUT}) and returns their stock. Each
 * cancellation is the same conditional transition used for DECLINED and EXHAUSTED, so two runs, or two api
 * instances, can never cancel an order or return its stock twice. ADR 0001, decision 9.
 */
@Component
class OrderExpiry {

	private static final Logger log = LoggerFactory.getLogger(OrderExpiry.class);

	private final JdbcClient jdbc;

	private final OrderTransitions transitions;

	private final Duration timeout;

	OrderExpiry(JdbcClient jdbc, OrderTransitions transitions, @Value("${app.orders.expiry.timeout}") Duration timeout) {
		this.jdbc = jdbc;
		this.transitions = transitions;
		this.timeout = timeout;
	}

	/** Returns how many orders this run cancelled. */
	@Scheduled(fixedDelayString = "${app.orders.expiry.interval}")
	int expireOverdue() {
		List<Long> overdue = jdbc.sql("""
				select id from orders
				where status = 'PENDING' and created_at < now() - make_interval(secs => :seconds)
				""")
				.param("seconds", timeout.toMillis() / 1000.0)
				.query(Long.class)
				.list();
		int expired = 0;
		for (var orderId : overdue) {
			if (transitions.cancel(orderId, "EXPIRED", "TIMEOUT")) {
				expired++;
				log.warn("Order {} stayed PENDING past {}; expired", orderId, timeout);
			}
		}
		return expired;
	}

}
