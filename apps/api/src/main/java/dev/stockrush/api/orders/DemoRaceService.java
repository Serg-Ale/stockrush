package dev.stockrush.api.orders;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

/**
 * The demo panel's race: many buyers order one unit of the same product at the same instant, through the same code
 * path as a real order, and the outcome is read back from the database. It exists to show that stock never goes
 * negative (ADR 0005).
 */
@Service
class DemoRaceService {

	private final OrderService orders;

	private final JdbcClient jdbc;

	private final int maxBuyers;

	DemoRaceService(OrderService orders, JdbcClient jdbc, @Value("${app.demo.max-buyers}") int maxBuyers) {
		this.orders = orders;
		this.jdbc = jdbc;
		this.maxBuyers = maxBuyers;
	}

	/**
	 * Runs the race as {@code adminEmail}. When {@code stock} is given it first becomes the product's available
	 * quantity, so the demo can be repeated; units still reserved by earlier pending orders are not touched.
	 */
	DemoRaceResult run(String adminEmail, long productId, int buyers, Integer stock, String paymentToken) {
		if (buyers > maxBuyers) {
			throw problem(HttpStatus.BAD_REQUEST, "At most " + maxBuyers + " buyers per race.");
		}
		if (stock != null) {
			var updated = jdbc.sql("update products set available_quantity = :stock where id = :id")
					.param("stock", stock).param("id", productId).update();
			if (updated == 0) {
				throw problem(HttpStatus.NOT_FOUND, "Product not found.");
			}
		}
		var stockBefore = stockOf(productId);
		var raceId = java.util.UUID.randomUUID();
		var start = new CountDownLatch(1);
		var outcomes = new ArrayList<Future<Outcome>>();
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var i = 0; i < buyers; i++) {
				var key = "race-" + raceId + "-" + i;
				outcomes.add(pool.submit(() -> {
					start.await();
					return attempt(adminEmail, key, productId, paymentToken);
				}));
			}
			start.countDown();
		}
		int sold = 0;
		int rejected = 0;
		int failed = 0;
		for (var outcome : outcomes) {
			switch (get(outcome)) {
				case SOLD -> sold++;
				case REJECTED -> rejected++;
				case FAILED -> failed++;
			}
		}
		return new DemoRaceResult(productId, buyers, sold, rejected, failed, stockBefore, stockOf(productId));
	}

	private Outcome attempt(String adminEmail, String key, long productId, String paymentToken) {
		try {
			orders.create(adminEmail, key, productId, 1, paymentToken);
			return Outcome.SOLD;
		}
		catch (ErrorResponseException e) {
			return e.getStatusCode().value() == HttpStatus.CONFLICT.value() ? Outcome.REJECTED : Outcome.FAILED;
		}
		catch (RuntimeException e) {
			return Outcome.FAILED;
		}
	}

	private int stockOf(long productId) {
		return jdbc.sql("select available_quantity from products where id = :id").param("id", productId)
				.query(Integer.class).optional()
				.orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "Product not found."));
	}

	private static Outcome get(Future<Outcome> outcome) {
		try {
			return outcome.get();
		}
		catch (Exception e) {
			return Outcome.FAILED;
		}
	}

	private static ErrorResponseException problem(HttpStatus status, String detail) {
		return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
	}

	private enum Outcome {
		SOLD, REJECTED, FAILED
	}

	/** {@code failed} is anything other than a sale or an out-of-stock rejection; it should be zero. */
	record DemoRaceResult(long productId, int buyers, int sold, int rejected, int failed, int stockBefore,
			int stockAfter) {
	}

}
