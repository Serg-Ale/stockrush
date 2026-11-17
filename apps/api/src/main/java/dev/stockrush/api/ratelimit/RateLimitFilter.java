package dev.stockrush.api.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fixed-window rate limit per client address, in memory (one api instance in v1). It runs before Spring Security, so
 * floods of unauthenticated requests are cut too. Over the limit the answer is 429 Problem Details with Retry-After.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RateLimitFilter extends OncePerRequestFilter {

	private record Window(long start, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	private final JsonMapper json;

	private final int limit;

	private final long windowMs;

	RateLimitFilter(JsonMapper json, @Value("${app.rate-limit.requests}") int limit,
			@Value("${app.rate-limit.window}") Duration window) {
		this.json = json;
		this.limit = limit;
		this.windowMs = window.toMillis();
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/actuator/health");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		var now = System.currentTimeMillis();
		var window = windows.merge(request.getRemoteAddr(), new Window(now, 1),
				(old, fresh) -> now - old.start() >= windowMs ? fresh : new Window(old.start(), old.count() + 1));
		if (window.count() <= limit) {
			chain.doFilter(request, response);
			return;
		}
		var retryAfter = Math.max(1, (window.start() + windowMs - now + 999) / 1000);
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
				"Too many requests. Try again in " + retryAfter + " s.");
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader("Retry-After", String.valueOf(retryAfter));
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write(json.writeValueAsString(problem));
	}

	/** Drops windows that have expired, so the map does not grow with every address ever seen. */
	@Scheduled(fixedDelayString = "${app.rate-limit.window}")
	void evictExpired() {
		var now = System.currentTimeMillis();
		windows.values().removeIf(w -> now - w.start() >= windowMs);
	}

}
