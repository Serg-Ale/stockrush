package dev.stockrush.api.auth;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stands in for the real ADMIN-only routes, which arrive in later tickets, to test the role wiring. */
@RestController
class AdminProbeController {

	@GetMapping("/admin/probe")
	@PreAuthorize("hasRole('ADMIN')")
	String probe() {
		return "ok";
	}

}
