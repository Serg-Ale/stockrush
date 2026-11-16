package dev.stockrush.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiApplicationTests {

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void healthReportsUpIncludingDatabase() throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build();

		var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	@Test
	void initialMigrationIsAppliedOnStartup() {
		var applied = jdbc.queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);

		assertThat(applied).contains("1");
	}

}
