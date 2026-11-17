package dev.stockrush.api.auth;

import org.springframework.security.core.Authentication;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearer")
class MeController {

	@GetMapping("/me")
	Me me(Authentication authentication) {
		return new Me(authentication.getName());
	}

	record Me(String email) {
	}

}
