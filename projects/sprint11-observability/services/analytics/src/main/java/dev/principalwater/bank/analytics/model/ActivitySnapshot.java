package dev.principalwater.bank.analytics.model;

import dev.principalwater.bank.analytics.dao.CassandraActivityStore;
import dev.principalwater.bank.contract.UiActivityEvent;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class ActivitySnapshot {
    private ActivitySnapshot() {}

    public static List<Row> build(
            UUID owner,
            long revision,
            List<CassandraActivityStore.Activity> backend,
            List<UiActivityEvent> ui) {
        Map<Key, Long> counts = new TreeMap<>();
        HashSet<UUID> seen = new HashSet<>();
        // PK Cassandra и PG inbox сохраняют immutable event; дедупликация предшествует любому
        // count.
        for (CassandraActivityStore.Activity event : backend) {
            if (seen.add(event.eventId()))
                counts.merge(
                        new Key(
                                event.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate(),
                                "BACKEND",
                                event.type().name(),
                                "NONE"),
                        1L,
                        Math::addExact);
        }
        for (UiActivityEvent event : ui) {
            if (seen.add(event.eventId()))
                counts.merge(
                        new Key(
                                event.occurredAt().atOffset(ZoneOffset.UTC).toLocalDate(),
                                "UI",
                                event.type().name(),
                                event.section().name()),
                        1L,
                        Math::addExact);
        }
        List<Row> rows = new ArrayList<>();
        counts.forEach(
                (key, count) ->
                        rows.add(
                                new Row(
                                        owner,
                                        key.date(),
                                        key.source(),
                                        key.type(),
                                        key.section(),
                                        count,
                                        revision)));
        return rows;
    }

    public record Row(
            UUID ownerId,
            LocalDate date,
            String source,
            String type,
            String section,
            long eventsCount,
            long revision) {}

    private record Key(LocalDate date, String source, String type, String section)
            implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int result = date.compareTo(other.date);
            if (result == 0) result = source.compareTo(other.source);
            if (result == 0) result = type.compareTo(other.type);
            return result == 0 ? section.compareTo(other.section) : result;
        }
    }
}
