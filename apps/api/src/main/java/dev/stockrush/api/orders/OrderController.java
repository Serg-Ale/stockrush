package dev.stockrush.api.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

	private final OrderService orders;

	OrderController(OrderService orders) {
		this.orders = orders;
	}

	@PostMapping("/orders")
	@ResponseStatus(HttpStatus.CREATED)
	OrderService.OrderResponse create(@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
		return orders.create(authentication.getName(), request.productId(), request.quantity());
	}

	record CreateOrderRequest(@NotNull Long productId, @Min(1) int quantity) {
	}

}
