package dev.stockrush.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chaos")
@PreAuthorize("hasRole('ADMIN')")
class ChaosController {

	private final ChaosSettings chaos;

	ChaosController(ChaosSettings chaos) {
		this.chaos = chaos;
	}

	@GetMapping
	ChaosSettings.Values get() {
		return chaos.get();
	}

	@PutMapping
	ChaosSettings.Values update(@Valid @RequestBody Request request) {
		chaos.set(new ChaosSettings.Values(request.failureRate(), request.latencyMs()));
		return chaos.get();
	}

	record Request(@DecimalMin("0.0") @DecimalMax("1.0") double failureRate, @Min(0) @Max(30_000) long latencyMs) {
	}

}
