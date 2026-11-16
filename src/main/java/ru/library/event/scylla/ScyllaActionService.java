
package ru.library.event.scylla;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class ScyllaActionService {

    private final ScyllaActionStore store;

    public ScyllaActionService(ScyllaActionStore store) {
        this.store = store;
    }

    public void record(String userId, String action, String eventId) {
        if (!available()) {
            return;
        }

        String actualUserId = userId == null ? "anonymous" : userId;
        String actualAction = action == null ? "UNKNOWN" : action;
        String actualObjectId = eventId == null ? "" : eventId;

        Instant now = Instant.now();

        ActionEvent event = new ActionEvent(
                UUID.randomUUID().toString(),
                actualUserId,
                actualAction,
                actualObjectId,
                now,
                LocalDate.ofInstant(now, ZoneOffset.UTC),
                Math.floorMod(actualObjectId.hashCode(), store.bucketCount())
        );

        store.append(event);
    }

    public boolean available() {
        return store.isAvailable();
    }

    public List<ActionEvent> objectEvents(
            String objectId,
            Instant from,
            Instant to,
            int limit,
            int offset
    ) {
        return store.findObjectEvents(objectId, from, to, limit, offset);
    }

    public List<ActionEvent> userEvents(
            String userId,
            LocalDate from,
            LocalDate to,
            int limit,
            int offset
    ) {
        return store.findUserEvents(userId, from, to, limit, offset);
    }

    public List<ActionEvent> actionEvents(
            String action,
            LocalDate from,
            LocalDate to,
            int limit,
            int offset
    ) {
        return store.findActionEvents(action, from, to, limit, offset);
    }
}
