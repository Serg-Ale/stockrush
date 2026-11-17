package dev.stockrush.api.catalog;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductController {

	private final ProductRepository products;

	ProductController(ProductRepository products) {
		this.products = products;
	}

	@Operation(operationId = "listProducts")
	@GetMapping("/products")
	List<ProductResponse> list() {
		return products.findAllByOrderByNameAsc().stream()
				.map(p -> new ProductResponse(p.getId(), p.getName(), p.getAvailableQuantity()))
				.toList();
	}

	record ProductResponse(Long id, String name, int availableQuantity) {
	}

}
