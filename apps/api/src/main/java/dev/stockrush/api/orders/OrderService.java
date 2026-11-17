package dev.stockrush.api.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

@Service
class OrderService {

	private final JdbcClient jdbc;

	OrderService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Reserves the stock and creates the PENDING order in one transaction. The decrement is a single
	 * conditional UPDATE, so concurrent orders can never take more units than exist.
	 */
	@Transactional
	OrderResponse create(String userEmail, long productId, int quantity) {
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
		return jdbc.sql("""
				insert into orders (user_id, product_id, quantity, status)
				values ((select id from users where email = :email), :productId, :quantity, 'PENDING')
				returning id, product_id, quantity, status
				""")
				.param("email", userEmail)
				.param("productId", productId)
				.param("quantity", quantity)
				.query((rs, i) -> new OrderResponse(rs.getLong("id"), rs.getLong("product_id"), rs.getInt("quantity"),
						rs.getString("status")))
				.single();
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
