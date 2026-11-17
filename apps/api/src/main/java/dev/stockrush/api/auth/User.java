package dev.stockrush.api.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String email;

	private String passwordHash;

	private String role;

	protected User() {
	}

	/** Self-registration always creates a CLIENTE. */
	User(String email, String passwordHash) {
		this(email, passwordHash, "CLIENTE");
	}

	User(String email, String passwordHash, String role) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = role;
	}

	Long getId() {
		return id;
	}

	String getEmail() {
		return email;
	}

	String getPasswordHash() {
		return passwordHash;
	}

	String getRole() {
		return role;
	}

}
