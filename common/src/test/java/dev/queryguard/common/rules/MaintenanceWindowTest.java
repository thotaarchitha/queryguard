package dev.queryguard.common.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaintenanceWindowTest {

    @Test
    void simpleWindow() {
        MaintenanceWindow w = new MaintenanceWindow(List.of("SAT", "SUN"), "02:00", "04:00", "UTC");
        assertThat(w.contains(Instant.parse("2026-11-07T02:00:00Z"))).isTrue();   // Sat
        assertThat(w.contains(Instant.parse("2026-11-07T04:00:00Z"))).isFalse();  // end is exclusive
        assertThat(w.contains(Instant.parse("2026-11-09T03:00:00Z"))).isFalse();  // Mon
    }

    @Test
    void windowAcrossMidnightBelongsToTheDayItStarted() {
        MaintenanceWindow w = new MaintenanceWindow(List.of("FRI"), "22:00", "02:00", "UTC");
        assertThat(w.contains(Instant.parse("2026-11-06T23:30:00Z"))).isTrue();   // Fri night
        assertThat(w.contains(Instant.parse("2026-11-07T01:30:00Z"))).isTrue();   // early Sat, still Fri's window
        assertThat(w.contains(Instant.parse("2026-11-08T01:30:00Z"))).isFalse();  // early Sun
    }

    @Test
    void timezoneIsRespected() {
        MaintenanceWindow ist = new MaintenanceWindow(null, "02:00", "04:00", "Asia/Kolkata");
        assertThat(ist.contains(Instant.parse("2026-11-03T21:00:00Z"))).isTrue(); // 02:30 IST
    }
}
