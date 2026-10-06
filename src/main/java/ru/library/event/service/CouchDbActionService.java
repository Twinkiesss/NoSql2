package ru.library.event.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import ru.library.event.couch.CouchDbClient;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class CouchDbActionService {
    private final CouchDbClient couch;

    public CouchDbActionService(CouchDbClient couch) {
        this.couch = couch;
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
        couch.put("/" + couch.database() + "/" + doc.get("_id").asText(), doc);
    }
}
