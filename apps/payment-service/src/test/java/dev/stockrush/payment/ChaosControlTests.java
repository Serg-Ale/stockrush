package dev.stockrush.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChaosControlTests {

	private final HttpClient http = HttpClient.newHttpClient();

	private final JsonMapper json = JsonMapper.builder().build();

	@LocalServerPort
	int port;

	@Autowired
	RabbitTemplate rabbit;

	@Value("${app.security.jwt-secret}")
	String secret;

	@AfterEach
	void resetChaos() throws Exception {
		put(token("ADMIN"), 0.0, 800);
	}

	@Test
	void onlyAdminCanReadOrChangeTheChaosSettings() throws Exception {
		assertThat(put(null, 0.5, 100).statusCode()).isEqualTo(401);
		assertThat(put(token("CLIENTE"), 0.5, 100).statusCode()).isEqualTo(403);
		assertThat(get(token("CLIENTE")).statusCode()).isEqualTo(403);

		var updated = put(token("ADMIN"), 0.25, 1500);

		assertThat(updated.statusCode()).isEqualTo(200);
		var current = json.readTree(get(token("ADMIN")).body());
		assertThat(current.path("failureRate").asDouble()).isEqualTo(0.25);
		assertThat(current.path("latencyMs").asLong()).isEqualTo(1500);
	}

	@Test
	void invalidValuesAreRejected() throws Exception {
		assertThat(put(token("ADMIN"), 1.5, 100).statusCode()).isEqualTo(400);
		assertThat(put(token("ADMIN"), 0.5, -1).statusCode()).isEqualTo(400);
	}

	@Test
	void changingTheFailureRateChangesBehaviorWithoutARestart() throws Exception {
		var before = orderCreated("tok_approve");
		assertThat(resultFor(before)).isEqualTo("APPROVED");

		put(token("ADMIN"), 1.0, 800);
		var failing = orderCreated("tok_approve");
		assertThat(resultFor(failing)).isEqualTo("EXHAUSTED");

		put(token("ADMIN"), 0.0, 800);
		var recovered = orderCreated("tok_approve");
		assertThat(resultFor(recovered)).isEqualTo("APPROVED");
	}

	@Test
	void slowTokenAnswersAfterTheConfiguredLatency() throws Exception {
		put(token("ADMIN"), 0.0, 1200);
		var start = System.nanoTime();
		var orderId = orderCreated("tok_slow");

		assertThat(resultFor(orderId)).isEqualTo("APPROVED");

		assertThat((System.nanoTime() - start) / 1_000_000).isBetween(1_200L, 6_000L);
	}

	private long orderCreated(String paymentToken) throws Exception {
		var orderId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		var body = json.writeValueAsBytes(Map.of("orderId", orderId, "paymentToken", paymentToken));
		rabbit.send("orders", "order.created", MessageBuilder.withBody(body).andProperties(properties).build());
		return orderId;
	}

	private String resultFor(long orderId) throws Exception {
		var deadline = System.nanoTime() + 20_000_000_000L;
		while (System.nanoTime() < deadline) {
			var message = rabbit.receive("payment.result", 500);
			if (message != null) {
				var tree = json.readTree(message.getBody());
				if (tree.path("orderId").asLong() == orderId) {
					return tree.path("result").asString();
				}
			}
		}
		return null;
	}

	private String token(String role) {
		var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var claims = JwtClaimsSet.builder().subject("test").claim("role", role).issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300)).build();
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		return new NimbusJwtEncoder(new ImmutableSecret<>(key)).encode(JwtEncoderParameters.from(header, claims))
				.getTokenValue();
	}

	private HttpResponse<String> get(String token) throws Exception {
		return http.send(request(token).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> put(String token, double failureRate, long latencyMs) throws Exception {
		var body = json.writeValueAsString(Map.of("failureRate", failureRate, "latencyMs", latencyMs));
		return http.send(request(token).header("Content-Type", "application/json")
				.PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest.Builder request(String token) {
		var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/chaos"));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return builder;
	}

}
