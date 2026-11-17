package dev.stockrush.api.orders;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The only ways an order leaves PENDING. Each is one conditional statement, so it applies at most once: repeated
 * or contradicting results find the order already final and change nothing.
 */
@Component
class OrderTransitions {

	private final JdbcClient jdbc;

	private final OrderEvents events;

	OrderTransitions(JdbcClient jdbc, OrderEvents events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	/** Returns whether the order was PENDING and is now CONFIRMED. */
	boolean confirm(long orderId) {
		var applied = jdbc.sql("update orders set status = 'CONFIRMED' where id = :id and status = 'PENDING'")
				.param("id", orderId)
				.update() == 1;
		notifyIf(applied, orderId);
		return applied;
	}

	/** Cancels a PENDING order and returns its reserved stock in the same statement. Returns whether it applied. */
	boolean cancel(long orderId, String reason, String internalReason) {
		var applied = jdbc.sql("""
				with cancelled as (
				    update orders set status = 'CANCELLED', cancellation_reason = :reason,
				        internal_cancellation_reason = :internalReason
				    where id = :id and status = 'PENDING'
				    returning product_id, quantity
				)
				update products p set available_quantity = p.available_quantity + c.quantity
				from cancelled c where p.id = c.product_id
				""")
				.param("id", orderId)
				.param("reason", reason)
				.param("internalReason", internalReason)
				.update() == 1;
		notifyIf(applied, orderId);
		return applied;
	}

	/** Each statement commits on its own, so the stream only ever sees committed states. */
	private void notifyIf(boolean applied, long orderId) {
		if (applied) {
			events.statusChanged(orderId);
		}
	}

}
