package dev.stockrush.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCatalogTests {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void listsSeededProductsWithAvailableQuantity() throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/products")).build();

		var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode products = JsonMapper.builder().build().readTree(response.body());
		assertThat(products.isArray()).isTrue();
		assertThat(products).isNotEmpty();
		for (JsonNode product : products) {
			assertThat(product.path("name").asString()).isNotBlank();
			assertThat(product.path("availableQuantity").isInt()).isTrue();
			// Sold-out products (DemoRaceTests drains the ones it creates, in the same database) are valid: only negative stock is not.
			assertThat(product.path("availableQuantity").asInt()).isNotNegative();
		}
	}

	@Test
	void seededProductsHavePicturesAsPathsInsideTheApp() throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/products")).build();

		var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		JsonNode products = JsonMapper.builder().build().readTree(response.body());
		var seeded = java.util.Set.of("Mechanical Keyboard", "Wireless Mouse", "USB-C Hub", "Noise-Cancelling Headphones");
		var withPicture = 0;
		for (JsonNode product : products) {
			var imageUrl = product.path("imageUrl");
			if (seeded.contains(product.path("name").asString())) {
				assertThat(imageUrl.asString()).matches("^/img/products/[a-z-]+\\.webp$");
				withPicture++;
			}
			else {
				assertThat(imageUrl.isNull() || imageUrl.isMissingNode() || imageUrl.asString().startsWith("/")).isTrue();
			}
		}
		assertThat(withPicture).isEqualTo(4);
	}

	@Test
	void theDatabaseRefusesAnythingButAPathInsideTheApp() {
		for (var bad : new String[] { "https://evil.example/x.png", "//evil.example/x.png", "/img/../secret.png", "img/x.webp",
				"/img/x.webp?track=1" }) {
			assertThatThrownBy(() -> jdbc.update("insert into products (name, available_quantity, image_url) values (?, 1, ?)",
					"bad-" + System.nanoTime(), bad)).isInstanceOf(DataIntegrityViolationException.class);
		}
	}

}
