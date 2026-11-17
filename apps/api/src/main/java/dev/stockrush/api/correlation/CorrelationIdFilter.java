package dev.stockrush.api.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id (the caller's {@code X-Correlation-Id} when it is a short safe token,
 * otherwise a new UUID), puts it in the logging context and echoes it in the response. The order service stores it
 * with the outbox event, so the same id reaches payment-service and comes back with the result.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Correlation-Id";

	private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var supplied = request.getHeader(HEADER);
		var id = supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
		MDC.put("correlationId", id);
		response.setHeader(HEADER, id);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove("correlationId");
		}
	}

}
