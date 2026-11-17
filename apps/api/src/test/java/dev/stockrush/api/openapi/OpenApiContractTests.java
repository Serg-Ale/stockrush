package dev.stockrush.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stockrush.api.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI document the running api publishes must equal the committed {@code openapi.json}, which is what the
 * web client is generated from. A controller change that is not reflected there fails here. To accept a change:
 * {@code ./mvnw test -Dtest=OpenApiContractTests -Dopenapi.update=true}, then regenerate the client.
 */
@Import(TestcontainersConfiguration.class)
// /admin/probe is a test-only controller, not part of the contract.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "springdoc.paths-to-exclude=/admin/probe")
class OpenApiContractTests {

	private static final Path COMMITTED = Path.of("openapi.json");

	private final JsonMapper json = JsonMapper.builder()
			.enable(SerializationFeature.INDENT_OUTPUT, SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.build();

	@LocalServerPort
	int port;

	@Test
	void publishedSpecificationMatchesTheCommittedOne() throws Exception {
		var response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		var current = json.writeValueAsString(json.readValue(response.body(), Object.class)) + "\n";

		if (Boolean.getBoolean("openapi.update")) {
			Files.writeString(COMMITTED, current);
		}

		assertThat(Files.readString(COMMITTED)).as("apps/api/openapi.json is out of date").isEqualTo(current);
	}

}
