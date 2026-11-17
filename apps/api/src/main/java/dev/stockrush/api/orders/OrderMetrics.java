package dev.stockrush.api.orders;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Gauges read from the database at scrape time, so they always match the committed state: orders per status and
 * the stock still available per product. Registered once the application is ready, because the products only
 * exist after the Flyway migrations ran.
 */
@Component
class OrderMetrics {

	private static final List<String> STATUSES = List.of("PENDING", "CONFIRMED", "CANCELLED");

	private final JdbcClient jdbc;

	private final MeterRegistry meters;

	OrderMetrics(JdbcClient jdbc, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.meters = meters;
	}

	@EventListener(ApplicationReadyEvent.class)
	void register() {
		for (var status : STATUSES) {
			Gauge.builder("orders", () -> jdbc.sql("select count(*) from orders where status = :status")
					.param("status", status).query(Long.class).single())
					.tag("status", status)
					.description("Orders by status")
					.register(meters);
		}
		for (var productId : jdbc.sql("select id from products").query(Long.class).list()) {
			Gauge.builder("stock_available", () -> jdbc.sql("select available_quantity from products where id = :id")
					.param("id", productId).query(Long.class).optional().orElse(0L))
					.tag("product", String.valueOf(productId))
					.description("Units of the product not reserved by any order")
					.register(meters);
		}
	}

}
