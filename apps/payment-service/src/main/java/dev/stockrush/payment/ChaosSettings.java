package dev.stockrush.payment;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The chaos control: runtime-adjustable failure rate and tok_slow latency. One instance, held in memory. */
@Component
class ChaosSettings {

	record Values(double failureRate, long latencyMs) {
	}

	private final AtomicReference<Values> current;

	private final Values defaults;

	ChaosSettings(@Value("${app.chaos.failure-rate}") double failureRate,
			@Value("${app.chaos.latency}") Duration latency) {
		this.defaults = new Values(failureRate, latency.toMillis());
		this.current = new AtomicReference<>(defaults);
	}

	Values get() {
		return current.get();
	}

	void set(Values values) {
		current.set(values);
	}

	/** Whether this attempt should fail temporarily, as if the gateway were unavailable. */
	boolean shouldFail() {
		return ThreadLocalRandom.current().nextDouble() < current.get().failureRate();
	}

	Duration latency() {
		return Duration.ofMillis(current.get().latencyMs());
	}

}
