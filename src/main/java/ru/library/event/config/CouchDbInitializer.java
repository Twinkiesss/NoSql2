package ru.library.event.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestClient;
import ru.library.event.couch.CouchDbClient;
import ru.library.event.service.CouchDbMigrationService;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(1)
public class CouchDbInitializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(CouchDbInitializer.class);

    private final CouchDbClient couch;
    private final CouchDbMigrationService migrationService;
    private final String replicaUrl;
    private final String username;
    private final String password;

    public CouchDbInitializer(
            CouchDbClient couch,
            CouchDbMigrationService migrationService,
            @Value("${couchdb.replica-url}") String replicaUrl,
            @Value("${couchdb.username}") String username,
            @Value("${couchdb.password}") String password) {
        this.couch = couch;
        this.migrationService = migrationService;
        this.replicaUrl = replicaUrl;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        ensureDatabase(couch);
        ensureReplicaDatabase();
        installDesignDocument();
        installIndex();
        migrationService.migrate();
        log.info("CouchDB initialized: primary={}, replica={}", couch.database(), replicaUrl);
    }

    private void ensureDatabase(CouchDbClient client) {
        try {
            client.put("/" + client.database(), Map.of());
        } catch (Exception ignored) {
        }
    }

    private void ensureReplicaDatabase() {
        RestClient replica = RestClient.builder()
                .baseUrl(replicaUrl)
                .defaultHeaders(h -> h.setBasicAuth(username, password))
                .build();
        try {
            replica.put().uri("/" + couch.database()).body(Map.of()).retrieve().toBodilessEntity();
        } catch (Exception ignored) {
        }
    }

    private void installDesignDocument() {
        Map<String, Object> views = new LinkedHashMap<>();
        views.put("user_actions", Map.of(
                "map", "function(doc) { if (doc.type === 'action') { emit([doc.userId, doc.action], 1); } }",
                "reduce", "_count"
        ));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_id", "_design/analytics");
        body.put("language", "javascript");
        body.put("validate_doc_update",
                "function(newDoc, oldDoc, userCtx, secObj) {" +
                "if (newDoc._deleted) return;" +
                "if (newDoc.schemaVersion !== 2) throw({forbidden:'schemaVersion must be 2'});" +
                "if (!newDoc.type) throw({forbidden:'type is required'});" +
                "if (newDoc.type === 'event') {" +
                " if (!newDoc._id || !newDoc.title || !newDoc.eventType || !newDoc.updatedAt) throw({forbidden:'event fields are required'});" +
                "}" +
                "}");
        body.put("views", views);
        JsonNode existing = couch.getOrNull("/" + couch.database() + "/_design/analytics");
        if (existing != null && existing.has("_rev")) {
            body.put("_rev", existing.get("_rev").asText());
        }
        couch.put("/" + couch.database() + "/_design/analytics", body);
    }

    private void installIndex() {
        createIndex("event-updated-at", java.util.List.of("type", "updatedAt"));
        createIndex("event-type-updated-at", java.util.List.of("type", "eventType", "updatedAt"));
    }

    private void createIndex(String name, java.util.List<String> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("index", Map.of(
                "fields", fields,
                "name", name,
                "type", "json"
        ));
        try {
            couch.post("/" + couch.database() + "/_index", body);
        } catch (Exception e) {
            log.debug("Mango index already exists: {}", e.getMessage());
        }
    }
}