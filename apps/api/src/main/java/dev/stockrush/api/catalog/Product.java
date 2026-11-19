package dev.stockrush.api.catalog;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name;

	private int availableQuantity;

	/** A path inside the web app (the database refuses anything else), or null when there is no picture. */
	private String imageUrl;

	protected Product() {
	}

	Long getId() {
		return id;
	}

	String getName() {
		return name;
	}

	int getAvailableQuantity() {
		return availableQuantity;
	}

	String getImageUrl() {
		return imageUrl;
	}

}
