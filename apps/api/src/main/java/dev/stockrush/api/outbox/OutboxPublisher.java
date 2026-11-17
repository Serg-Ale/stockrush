package dev.stockrush.api.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relays unpublished outbox rows to RabbitMQ. Rows are claimed with {@code FOR UPDATE SKIP LOCKED}, so several
 * instances can run at once and never publish the same row in parallel. Delivery is at-least-once: if the process
 * dies after the broker confirms but before the commit, the row is published again, so consumers must be
 * idempotent.
 */
@Component
public class OutboxPublisher {

	public static final String EXCHANGE = "orders";

	public static final String CORRELATION_ID_HEADER = "x-correlation-id";

	public static final String ORDER_CREATED = "order.created";

	private record Row(long id, String type, String correlationId, String payload) {
	}

	private final JdbcClient jdbc;

	private final RabbitTemplate rabbit;

	private final int batchSize;

	private final Duration confirmTimeout;

	OutboxPublisher(JdbcClient jdbc, RabbitTemplate rabbit, @Value("${app.outbox.batch-size}") int batchSize,
			@Value("${app.outbox.confirm-timeout}") Duration confirmTimeout) {
		this.jdbc = jdbc;
		this.rabbit = rabbit;
		this.batchSize = batchSize;
		this.confirmTimeout = confirmTimeout;
	}

	@Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
	void poll() {
		publishPending();
	}

	/** Publishes one batch and returns how many rows were sent. */
	@Transactional
	public int publishPending() {
		List<Row> rows = jdbc.sql("""
				select id, type, correlation_id, payload::text as payload from outbox
				where published_at is null
				order by id limit :limit
				for update skip locked
				""")
				.param("limit", batchSize)
				.query((rs, i) -> new Row(rs.getLong("id"), rs.getString("type"), rs.getString("correlation_id"),
						rs.getString("payload")))
				.list();
		for (var row : rows) {
			publish(row);
			jdbc.sql("update outbox set published_at = now() where id = :id").param("id", row.id()).update();
		}
		return rows.size();
	}

	private void publish(Row row) {
		var properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(String.valueOf(row.id()));
		if (row.correlationId() != null) {
			properties.setHeader(CORRELATION_ID_HEADER, row.correlationId());
		}
		Message message = MessageBuilder.withBody(row.payload().getBytes(StandardCharsets.UTF_8))
				.andProperties(properties)
				.build();
		rabbit.invoke(operations -> {
			operations.send(EXCHANGE, row.type(), message);
			operations.waitForConfirmsOrDie(confirmTimeout.toMillis());
			return null;
		});
	}

	@Bean
	DirectExchange ordersExchange() {
		return new DirectExchange(EXCHANGE);
	}

	@Bean
	Queue orderCreatedQueue() {
		return QueueBuilder.durable(ORDER_CREATED).build();
	}

	@Bean
	Binding orderCreatedBinding(Queue orderCreatedQueue, DirectExchange ordersExchange) {
		return BindingBuilder.bind(orderCreatedQueue).to(ordersExchange).with(ORDER_CREATED);
	}

}
