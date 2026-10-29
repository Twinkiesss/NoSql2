package ru.library.event.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClientResponseException;
import ru.library.event.couch.CouchDbClient;
import ru.library.event.model.Event;

import java.time.LocalDateTime;
import java.util.*;

@Repository
public class CouchDbEventRepository {
    private final CouchDbClient couch;

    public CouchDbEventRepository(CouchDbClient couch) {
        this.couch = couch;
    }

    public synchronized Event save(Event event) {
        if (event.getId() == null || event.getId().isBlank()) {
            event.setId(UUID.randomUUID().toString());
        }
        JsonNode result = couch.put("/" + couch.database() + "/" + event.getId(), toDocument(event));
        if (result.has("rev")) event.setRev(result.get("rev").asText());
        return event;
    }

    public synchronized Event saveWithRevision(Event event) {
        try {
            return save(event);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new ConflictException(event.getId());
            }
            throw e;
        }
    }

    public Event findById(String id) {
        JsonNode node = couch.getOrNull("/" + couch.database() + "/" + id);
        if (node == null || node.has("_deleted")) return null;
        return fromDocument(node);
    }

    public List<Event> findAll() {
        JsonNode result = couch.find(Map.of("type", "event"), List.of(), 10000, 0, null);
        List<Event> events = new ArrayList<>();
        result.path("docs").forEach(n -> events.add(fromDocument(n)));
        return events;
    }

    public void delete(String id, String rev) {
        couch.delete("/" + couch.database() + "/" + id + "?rev=" + rev);
    }

    public List<Event> search(String type, LocalDateTime from, LocalDateTime to,
                              String nestedState, String q, String category, boolean availableOnly,
                              String sort, boolean descending,
                              int page, int size) {
        Map<String, Object> selector = buildSearchSelector(type, from, to, nestedState, q, category, availableOnly);
        String field = sort == null || sort.isBlank() ? "updatedAt" : sort;

        JsonNode result = couch.find(selector, List.of(), 10000, 0, null);
        List<Event> events = new ArrayList<>();
        result.path("docs").forEach(n -> events.add(fromDocument(n)));
        events.sort(comparator(field, descending));
        int fromIdx = Math.min(page * size, events.size());
        int toIdx = Math.min(fromIdx + size, events.size());
        return new ArrayList<>(events.subList(fromIdx, toIdx));
    }

    private static Comparator<Event> comparator(String field, boolean descending) {
        Comparator<Event> cmp;
        switch (field) {
            case "title": cmp = Comparator.comparing(Event::getTitle, Comparator.nullsLast(Comparator.naturalOrder())); break;
            case "author": cmp = Comparator.comparing(Event::getAuthor, Comparator.nullsLast(Comparator.naturalOrder())); break;
            case "category": cmp = Comparator.comparing(Event::getCategory, Comparator.nullsLast(Comparator.naturalOrder())); break;
            case "availableCopies": cmp = Comparator.comparingInt(Event::getAvailableCopies); break;
            case "viewCount": cmp = Comparator.comparingLong(Event::getViewCount); break;
            case "createdAt": cmp = Comparator.comparing(Event::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())); break;
            default: cmp = Comparator.comparing(Event::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder())); break;
        }
        return descending ? cmp.reversed() : cmp;
    }

    public int countSearch(String type, LocalDateTime from, LocalDateTime to,
                           String nestedState, String q, String category, boolean availableOnly) {
        return couch.count(buildSearchSelector(type, from, to, nestedState, q, category, availableOnly));
    }

    private Map<String, Object> buildSearchSelector(String type, LocalDateTime from, LocalDateTime to,
                                                    String nestedState, String q, String category,
                                                    boolean availableOnly) {
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("type", "event");

        if (type != null && !type.isBlank()) {
            selector.put("eventType", type);
        }
        if (from != null || to != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (from != null) range.put("$gte", from.toString());
            if (to != null) range.put("$lte", to.toString());
            selector.put("updatedAt", range);
        }
        if (nestedState != null && !nestedState.isBlank()) {
            selector.put("stateHistory.state", nestedState);
        }
        if (category != null && !category.isBlank()) {
            selector.put("category", category);
        }
        if (availableOnly) {
            selector.put("availableCopies", Map.of("$gt", 0));
        }
        if (q != null && !q.isBlank()) {
            String escaped = q.trim().replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
            selector.put("$or", java.util.List.of(
                    Map.of("title", Map.of("$regex", "(?i)" + escaped)),
                    Map.of("author", Map.of("$regex", "(?i)" + escaped))
            ));
        }
        return selector;
    }

    public JsonNode explain(String type, LocalDateTime from, LocalDateTime to) {
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("type", "event");
        if (type != null && !type.isBlank()) selector.put("eventType", type);
        if (from != null || to != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (from != null) range.put("$gte", from.toString());
            if (to != null) range.put("$lte", to.toString());
            selector.put("updatedAt", range);
        }
        return couch.explain(selector, List.of());
    }

    public List<Event> findByCategory(String category) {
        JsonNode result = couch.find(
                Map.of("type", "event", "category", category),
                List.of(), 10000, 0, null);
        List<Event> events = new ArrayList<>();
        result.path("docs").forEach(n -> events.add(fromDocument(n)));
        events.sort(comparator("updatedAt", true));
        return events;
    }

    private ObjectNode toDocument(Event e) {
        ObjectNode n = couch.mapper().valueToTree(e);
        n.remove("rev");
        n.put("_id", e.getId());
        if (e.getRev() != null && !e.getRev().isBlank()) n.put("_rev", e.getRev());
        n.put("type", "event");
        n.put("eventType", e.getType() == null ? "book" : e.getType());
        n.put("schemaVersion", 2);
        return n;
    }

    private Event fromDocument(JsonNode n) {
        ObjectNode copy = n.deepCopy();
        String id = copy.path("_id").asText();
        String rev = copy.path("_rev").asText(null);
        copy.remove("_id");
        copy.remove("_rev");
        copy.remove("type");
        copy.remove("eventType");
        Event event = couch.mapper().convertValue(copy, Event.class);
        event.setId(id);
        event.setRev(rev);
        if (event.getType() == null) event.setType("book");
        return event;
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String id) {
            super("CouchDB conflict: document " + id + " has another revision");
        }
    }
}
