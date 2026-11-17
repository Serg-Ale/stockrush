package dev.stockrush.api.orders;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearer")
class DemoRaceController {

	private final DemoRaceService race;

	DemoRaceController(DemoRaceService race) {
		this.race = race;
	}

	@Operation(operationId = "runDemoRace")
	@PostMapping("/admin/race")
	@PreAuthorize("hasRole('ADMIN')")
	DemoRaceService.DemoRaceResult run(@Valid @RequestBody RaceRequest request, Authentication authentication) {
		return race.run(authentication.getName(), request.productId(), request.buyers(), request.stock(),
				request.paymentToken());
	}

	/** {@code stock}, when present, replaces the product's available quantity before the race. */
	record RaceRequest(@NotNull Long productId, @Min(1) int buyers, @Min(0) @Max(10_000) Integer stock,
			@Size(max = 64) String paymentToken) {

		RaceRequest {
			if (paymentToken == null || paymentToken.isBlank()) {
				paymentToken = "tok_approve";
			}
		}

	}

}
