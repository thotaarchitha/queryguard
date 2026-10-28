package dev.queryguard.proxy.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query events to Kafka without ever making a query wait.
 *
 * <p>The event loop only does {@code queue.offer()}. A separate thread drains the queue into
 * the producer. KafkaProducer.send() itself can block (metadata fetch, full buffer, up to
 * max.block.ms), which is fine on that thread and would be terrible on the event loop.
 *
 * <p>If Kafka is down or slow, the queue fills, offers fail, and events are dropped and counted
 * ({@code qg_events_dropped_total}). Losing audit events during a Kafka outage is the trade
 * I'm making so that a logging problem can never become a database outage.
 */
public final class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final ObjectMapper json = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();
    private final BlockingQueue<QueryEvent> queue;
    private final KafkaProducer<String, byte[]> producer;
    private final String topic;
    private final Counter dropped;
    private final Counter sent;
    private final Thread sender;
    private volatile boolean running = true;

    public KafkaEventPublisher(String bootstrap, String topic, int queueCapacity, MeterRegistry registry) {
        this.topic = topic;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.dropped = Counter.builder("qg_events_dropped").description("Query events dropped (queue full or Kafka error)").register(registry);
        this.sent = Counter.builder("qg_events_sent").description("Query events acknowledged by Kafka").register(registry);
        registry.gauge("qg_events_queue_depth", queue, BlockingQueue::size);

        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.ACKS_CONFIG, "1");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "false"); // idempotence needs acks=all
        p.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(64 * 1024));
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "2000");
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000");
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        p.put(ProducerConfig.CLIENT_ID_CONFIG, "queryguard-proxy");
        this.producer = new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());

        this.sender = Thread.ofPlatform().name("qg-events").daemon().start(this::drain);
    }

    @Override
    public void publish(QueryEvent event) {
        if (!queue.offer(event)) {
            dropped.increment();
        }
    }

    private void drain() {
        while (running || !queue.isEmpty()) {
            QueryEvent e;
            try {
                e = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (e == null) {
                continue;
            }
            try {
                byte[] value = json.writeValueAsBytes(e);
                // Keyed by fingerprint: the same query shape always lands on the same
                // partition, which keeps per-shape ordering if anyone ever needs it.
                producer.send(new ProducerRecord<>(topic, e.fingerprint(), value), (meta, err) -> {
                    if (err != null) {
                        dropped.increment();
                    } else {
                        sent.increment();
                    }
                });
            } catch (Exception ex) {
                dropped.increment();
                log.debug("event dropped: {}", ex.toString());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            sender.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        producer.close(java.time.Duration.ofSeconds(5));
    }
}
