package dev.stockrush.api.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the demonstration ADMIN at startup, only when {@code app.demo-admin.password} is set. Production leaves
 * it unset, so there is no default credential to leak; the demo environment sets it through DEMO_ADMIN_PASSWORD.
 */
@Component
class DemoAdminSeeder implements ApplicationRunner {

	private final UserRepository users;

	private final PasswordEncoder encoder;

	private final String email;

	private final String password;

	DemoAdminSeeder(UserRepository users, PasswordEncoder encoder, @Value("${app.demo-admin.email}") String email,
			@Value("${app.demo-admin.password:}") String password) {
		this.users = users;
		this.encoder = encoder;
		this.email = email;
		this.password = password;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (password.isBlank() || users.findByEmail(email).isPresent()) {
			return;
		}
		users.save(new User(email, encoder.encode(password), "ADMIN"));
	}

}
