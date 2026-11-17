package dev.stockrush.api.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
class AuthController {

	// Compared against when the email is unknown, so both login failures cost the same.
	private final String dummyHash;

	private final UserRepository users;

	private final PasswordEncoder encoder;

	private final JwtEncoder jwtEncoder;

	private final Duration ttl;

	AuthController(UserRepository users, PasswordEncoder encoder, JwtEncoder jwtEncoder,
			@Value("${app.security.jwt-ttl}") Duration ttl) {
		this.users = users;
		this.encoder = encoder;
		this.jwtEncoder = jwtEncoder;
		this.ttl = ttl;
		this.dummyHash = encoder.encode("unknown-user");
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	RegisterResponse register(@Valid @RequestBody Credentials credentials) {
		var email = normalize(credentials.email());
		if (users.findByEmail(email).isPresent()) {
			throw emailTaken();
		}
		try {
			var saved = users.saveAndFlush(new User(email, encoder.encode(credentials.password())));
			return new RegisterResponse(saved.getId(), saved.getEmail());
		}
		catch (DataIntegrityViolationException e) {
			throw emailTaken();
		}
	}

	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody Credentials credentials) {
		var user = users.findByEmail(normalize(credentials.email())).orElse(null);
		var matches = encoder.matches(credentials.password(), user != null ? user.getPasswordHash() : dummyHash);
		if (user == null || !matches) {
			var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password.");
			throw new ErrorResponseException(HttpStatus.UNAUTHORIZED, problem, null);
		}
		var now = Instant.now();
		var claims = JwtClaimsSet.builder()
				.subject(user.getEmail())
				.issuedAt(now)
				.expiresAt(now.plus(ttl))
				.build();
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		return new TokenResponse(jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue());
	}

	private static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static ErrorResponseException emailTaken() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Email is already registered.");
		return new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
	}

	record Credentials(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 72) String password) {
	}

	record RegisterResponse(Long id, String email) {
	}

	record TokenResponse(String token) {
	}

}
