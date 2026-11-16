package dev.stockrush.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
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
			assertThat(product.path("availableQuantity").asInt()).isPositive();
		}
	}

}
