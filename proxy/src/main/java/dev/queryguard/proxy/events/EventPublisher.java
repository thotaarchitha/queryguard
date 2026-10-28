package dev.queryguard.proxy.events;

public interface EventPublisher extends AutoCloseable {

    /** Must never block. Called on the event loop. */
    void publish(QueryEvent event);

    @Override
    void close();

    EventPublisher NOOP = new EventPublisher() {
        @Override
        public void publish(QueryEvent event) {
        }

        @Override
        public void close() {
        }
    };
}
