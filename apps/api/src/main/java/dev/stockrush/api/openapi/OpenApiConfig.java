package dev.stockrush.api.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The published contract is generated from the controllers; this only adds its title and the bearer scheme. */
@Configuration
class OpenApiConfig {

	/** Name used by {@code @SecurityRequirement} on the controllers that need a token. */
	static final String BEARER = "bearer";

	static {
		// The stream endpoint returns an SseEmitter; its body is just text events, not the emitter's fields.
		SpringDocUtils.getConfig().replaceWithClass(SseEmitter.class, String.class);
	}

	@Bean
	OpenAPI stockrushOpenApi() {
		return new OpenAPI()
				.info(new Info().title("Stockrush API").version("v1")
						.description("Orders with limited stock. Errors are RFC 9457 Problem Details."))
				// Relative, so the document does not change with the host and port it was fetched from.
				.servers(List.of(new Server().url("/")))
				.components(new Components().addSecuritySchemes(BEARER,
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}

}
