package dev.queryguard.proxy.config;

import java.util.concurrent.atomic.AtomicReference;

/** The one mutable spot: an AtomicReference swapped by the config watcher, read by every query. */
public final class PolicyHolder {

    private final AtomicReference<ActivePolicy> current;

    public PolicyHolder(ActivePolicy initial) {
        this.current = new AtomicReference<>(initial);
    }

    public ActivePolicy current() {
        return current.get();
    }

    public void swap(ActivePolicy next) {
        current.set(next);
    }
}
