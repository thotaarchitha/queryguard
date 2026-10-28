package dev.queryguard.common.rules;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * {@code { days: [SAT, SUN], from: "02:00", to: "04:00", tz: "UTC" }}.
 * A window that wraps midnight ({@code from: "22:00", to: "02:00"}) works too.
 */
public record MaintenanceWindow(List<String> days, String from, String to, String tz) {

    public boolean contains(Instant now) {
        ZonedDateTime local = now.atZone(tz == null ? ZoneId.of("UTC") : ZoneId.of(tz));
        LocalTime start = LocalTime.parse(from == null ? "00:00" : from);
        LocalTime end = LocalTime.parse(to == null ? "23:59" : to);
        LocalTime t = local.toLocalTime();

        boolean wraps = end.isBefore(start);
        boolean inTime = wraps ? (!t.isBefore(start) || t.isBefore(end)) : (!t.isBefore(start) && t.isBefore(end));
        if (!inTime) {
            return false;
        }
        if (days == null || days.isEmpty()) {
            return true;
        }
        // After midnight in a wrapping window, the window "belongs" to the previous day.
        DayOfWeek day = wraps && t.isBefore(end) ? local.getDayOfWeek().minus(1) : local.getDayOfWeek();
        String abbrev = day.name().substring(0, 3);
        return days.stream().anyMatch(d -> d.toUpperCase(Locale.ROOT).startsWith(abbrev));
    }
}
