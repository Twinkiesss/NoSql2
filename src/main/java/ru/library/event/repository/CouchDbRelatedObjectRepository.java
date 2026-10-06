package ru.library.event.repository;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Repository;
import ru.library.event.couch.CouchDbClient;
import ru.library.event.model.RelatedObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class CouchDbRelatedObjectRepository {
    private final CouchDbClient couch;

    public CouchDbRelatedObjectRepository(CouchDbClient couch) {
        this.couch = couch;
    }

    public RelatedObject save(RelatedObject object) {
        JsonNode doc = couch.mapper().valueToTree(object);
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("_id", object.getId());
        ((com.fasterxml.jackson.databind.node.ObjectNode) doc).put("schemaVersion", 2);
        JsonNode result = couch.put("/" + couch.database() + "/" + object.getId(), doc);
        return object;
    }

    public List<RelatedObject> findByEventId(String eventId) {
        JsonNode result = couch.find(
                Map.of("type", "relatedObject", "eventId", eventId),
                Map.of("createdAt", "asc"), 10000, 0, null);
        List<RelatedObject> objects = new ArrayList<>();
        result.path("docs").forEach(n -> {
            var copy = n.deepCopy();
            copy.remove("_id");
            copy.remove("_rev");
            objects.add(couch.mapper().convertValue(copy, RelatedObject.class));
            objects.get(objects.size() - 1).setId(n.path("_id").asText());
        });
        return objects;
    }

    public void delete(String id, String rev) {
        couch.delete("/" + couch.database() + "/" + id + "?rev=" + rev);
    }
}
