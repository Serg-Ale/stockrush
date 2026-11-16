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

}
