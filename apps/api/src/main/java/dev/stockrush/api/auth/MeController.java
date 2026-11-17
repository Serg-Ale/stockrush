package dev.stockrush.api.auth;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MeController {

	@GetMapping("/me")
	Me me(Authentication authentication) {
		return new Me(authentication.getName());
	}

	record Me(String email) {
	}

}
