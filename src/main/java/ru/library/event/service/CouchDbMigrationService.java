package ru.library.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import ru.library.event.couch.CouchDbClient;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CouchDbMigrationService {
    private final CouchDbClient couch;

    public CouchDbMigrationService(CouchDbClient couch) {
        this.couch = couch;
    }

    public int migrate() {
        JsonNode all = couch.get("/" + couch.database() + "/_all_docs?include_docs=true&limit=10000");
        int migrated = 0;
        for (JsonNode row : all.path("rows")) {
            JsonNode doc = row.path("doc");
            if (doc.isMissingNode() || doc.path("_id").asText().startsWith("_design/")) continue;
            if (!"event".equals(doc.path("type").asText()) && !"event".equals(doc.path("docType").asText())) continue;

            ObjectNode updated = doc.deepCopy();
            if (updated.path("schemaVersion").asInt(1) < 2) {
                updated.put("schemaVersion", 2);
                if (!updated.has("eventType")) updated.put("eventType", "book");
                if (!updated.has("type")) updated.put("type", "event");
                if (!updated.has("stateHistory")) updated.set("stateHistory", couch.mapper().createArrayNode());
                if (!updated.has("lastChange")) {
                    updated.set("lastChange", couch.mapper().valueToTree(
                            new LinkedHashMap<>(Map.of(
                                    "action", "MIGRATE",
                                    "userId", "migration",
                                    "changedAt", LocalDateTime.now().toString()
                            ))));
                }
                if (!updated.has("updatedAt")) updated.put("updatedAt", LocalDateTime.now().toString());
                couch.put("/" + couch.database() + "/" + updated.path("_id").asText(), updated);
                migrated++;
            }
        }
        return migrated;
    }
}
