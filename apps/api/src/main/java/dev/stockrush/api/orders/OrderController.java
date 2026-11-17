package dev.stockrush.api.orders;

import jakarta.validation.Valid;
import java.util.List;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
class OrderController {

	private final OrderService orders;

	private final OrderEvents events;

	OrderController(OrderService orders, OrderEvents events) {
		this.orders = orders;
		this.events = events;
	}

	@PostMapping("/orders")
	@ResponseStatus(HttpStatus.CREATED)
	OrderService.OrderResponse create(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
			@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
		return orders.create(authentication.getName(), idempotencyKey, request.productId(), request.quantity(),
				request.paymentToken());
	}

	@GetMapping("/orders")
	List<OrderService.OrderResponse> list(Authentication authentication) {
		return orders.list(authentication.getName());
	}

	/** Server-sent events: one {@code order-status} event per status change of the caller's orders. */
	@GetMapping(path = "/orders/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter stream(Authentication authentication) {
		return events.subscribe(authentication.getName());
	}

	@GetMapping("/orders/{id}")
	OrderService.OrderResponse get(@PathVariable long id, Authentication authentication) {
		return orders.get(authentication.getName(), id);
	}

	record CreateOrderRequest(@NotNull Long productId, @Min(1) int quantity, @Size(max = 64) String paymentToken) {

		CreateOrderRequest {
			if (paymentToken == null || paymentToken.isBlank()) {
				paymentToken = "tok_approve";
			}
		}

	}

}
