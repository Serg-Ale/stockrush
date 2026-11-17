package dev.stockrush.api.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

@Service
class OrderService {

	private static final RowMapper<OrderResponse> ORDER = (rs, i) -> new OrderResponse(rs.getLong("id"),
			rs.getLong("product_id"), rs.getInt("quantity"), rs.getString("status"));

	private final JdbcClient jdbc;

	OrderService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Creates the PENDING order and reserves its stock in one transaction. The order row is inserted first, keyed
	 * by (user, idempotency key): a concurrent or repeated request with the same key conflicts on the unique index
	 * and replays the stored order instead of reserving again. The stock decrement is a single conditional UPDATE,
	 * so concurrent orders can never take more units than exist.
	 */
	@Transactional
	OrderResponse create(String userEmail, String idempotencyKey, long productId, int quantity) {
		var created = jdbc.sql("""
				insert into orders (user_id, product_id, quantity, status, idempotency_key)
				select u.id, p.id, :quantity, 'PENDING', :key
				from users u, products p where u.email = :email and p.id = :productId
				on conflict (user_id, idempotency_key) do nothing
				returning id, product_id, quantity, status
				""")
				.param("email", userEmail)
				.param("productId", productId)
				.param("quantity", quantity)
				.param("key", idempotencyKey)
				.query(ORDER)
				.optional();
		if (created.isEmpty()) {
			return replay(userEmail, idempotencyKey, productId, quantity);
		}
		var reserved = jdbc.sql("""
				update products set available_quantity = available_quantity - :quantity
				where id = :productId and available_quantity >= :quantity
				""")
				.param("quantity", quantity)
				.param("productId", productId)
				.update();
		if (reserved == 0) {
			throw rejection(productId);
		}
		return created.get();
	}

	private OrderResponse replay(String userEmail, String idempotencyKey, long productId, int quantity) {
		var existing = jdbc.sql("""
				select o.id, o.product_id, o.quantity, o.status from orders o
				join users u on u.id = o.user_id
				where u.email = :email and o.idempotency_key = :key
				""")
				.param("email", userEmail)
				.param("key", idempotencyKey)
				.query(ORDER)
				.optional()
				.orElseThrow(() -> rejection(productId));
		if (existing.productId() != productId || existing.quantity() != quantity) {
			var status = HttpStatus.UNPROCESSABLE_ENTITY;
			throw new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status,
					"Idempotency-Key was already used with a different request."), null);
		}
		return existing;
	}

	private ErrorResponseException rejection(long productId) {
		var exists = jdbc.sql("select exists (select 1 from products where id = :id)")
				.param("id", productId)
				.query(Boolean.class)
				.single();
		var status = exists ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
		var detail = exists ? "Insufficient stock for the requested quantity." : "Product not found.";
		return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
	}

	record OrderResponse(long id, long productId, int quantity, String status) {
	}

}
