package ru.library.event.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import ru.library.event.couch.CouchDbClient;
import ru.library.event.scylla.ScyllaActionService;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class CouchDbActionService {

    private final CouchDbClient couch;
    private final ScyllaActionService scylla;

    public CouchDbActionService(
            CouchDbClient couch,
            ScyllaActionService scylla
    ) {
        this.couch = couch;
        this.scylla = scylla;
    }

    public void log(String userId, String action, String eventId) {
        ObjectNode doc = couch.mapper().createObjectNode();

        doc.put("_id", "action-" + UUID.randomUUID());
        doc.put("type", "action");
        doc.put("schemaVersion", 2);
        doc.put("userId", userId == null ? "anonymous" : userId);
        doc.put("action", action);
        doc.put("eventId", eventId == null ? "" : eventId);
        doc.put("changedAt", LocalDateTime.now().toString());

        // Сохраняем журнал в CouchDB.
        couch.put(
                "/" + couch.database() + "/" + doc.get("_id").asText(),
                doc
        );

        // Дополнительно записываем действие в ScyllaDB.
        scylla.record(userId, action, eventId);
    }
}