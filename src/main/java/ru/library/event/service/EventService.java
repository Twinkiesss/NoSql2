package ru.library.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.library.event.couch.CouchDbClient;
import ru.library.event.model.Event;
import ru.library.event.model.EventSearchRequest;
import ru.library.event.model.EventSort;
import ru.library.event.model.PageResponse;
import ru.library.event.repository.CouchDbEventRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final CouchDbEventRepository eventRepository;
    private final CouchDbMigrationService migrationService;
    private final CouchDbActionService actionService;
    private final EtcdQueryCacheService queryCache;
    private final CouchDbClient couch;
    private final String replicaUrl;
    private final String username;
    private final String password;

    public EventService(CouchDbEventRepository eventRepository,
                        CouchDbMigrationService migrationService,
                        CouchDbActionService actionService,
                        EtcdQueryCacheService queryCache,
                        CouchDbClient couch,
                        @Value("${couchdb.replica-url}") String replicaUrl,
                        @Value("${couchdb.username}") String username,
                        @Value("${couchdb.password}") String password) {
        this.eventRepository = eventRepository;
        this.migrationService = migrationService;
        this.actionService = actionService;
        this.queryCache = queryCache;
        this.couch = couch;
        this.replicaUrl = replicaUrl;
        this.username = username;
        this.password = password;
    }

    // ---------- CRUD ----------

    public Event createEvent(Event event, String actorId) {
        Event saved = eventRepository.saveWithRevision(event);
        invalidateSearchCache();
        actionService.log(actorId, "CREATE", saved.getId());
        log.info("Event created in CouchDB: id={}, by={}", saved.getId(), actorId);
        return saved;
    }

    public Optional<Event> getEvent(String id) {
        return Optional.ofNullable(eventRepository.findById(id));
    }

    public List<Event> getAllEvents() {
        return eventRepository.findAll();
    }

    public Event updateEvent(Event event, String actorId) {
        Event saved = eventRepository.saveWithRevision(event);
        invalidateSearchCache();
        actionService.log(actorId, "UPDATE", saved.getId());
        return saved;
    }

    public void deleteEvent(String id, String actorId) {
        Event existing = eventRepository.findById(id);
        if (existing == null) return;
        eventRepository.delete(id, existing.getRev());
        invalidateSearchCache();
        actionService.log(actorId, "DELETE", id);
    }

    // ---------- views / copies ----------

    public long incrementViewCount(String eventId, String userId) {
        Event event = eventRepository.findById(eventId);
        if (event == null) throw new ru.library.event.exception.EventNotFoundException(eventId);
        event.setViewCount(event.getViewCount() + 1);
        eventRepository.saveWithRevision(event);
        actionService.log(userId, "VIEW", eventId);
        return event.getViewCount();
    }

    public long getViewCount(String eventId) {
        Event event = eventRepository.findById(eventId);
        return event == null ? 0L : event.getViewCount();
    }

    public int reserveOneCopy(String eventId) {
        Event event = eventRepository.findById(eventId);
        if (event == null || event.getAvailableCopies() <= 0) return 0;
        event.setAvailableCopies(event.getAvailableCopies() - 1);
        eventRepository.saveWithRevision(event);
        invalidateSearchCache();
        return 1;
    }

    public int releaseOneCopy(String eventId) {
        Event event = eventRepository.findById(eventId);
        if (event == null) return 0;
        event.setAvailableCopies(event.getAvailableCopies() + 1);
        eventRepository.saveWithRevision(event);
        invalidateSearchCache();
        return 1;
    }

    public List<Event> getEventsByCategory(String category) {
        return eventRepository.findByCategory(category);
    }

    // ---------- search ----------

    public PageResponse<Event> searchPage(EventSearchRequest request) {
        EventSort sort = request.getSort() == null ? EventSort.UPDATED_AT : request.getSort();
        String sortField = sort.getField();
        int page = Math.max(0, request.getPage());
        int size = Math.max(1, Math.min(100, request.getSize()));

        String cacheKey = "search:" + sortField + ":" + request.isDesc() + ":" + page + ":" + size
                + ":" + request.getType() + ":" + request.getFrom() + ":" + request.getTo()
                + ":" + request.getQ() + ":" + request.getCategory() + ":" + request.isAvailableOnly();

        Optional<JsonNode> cached = queryCache.get(cacheKey);
        if (cached.isPresent()) {
            return couch.mapper().convertValue(cached.get(), PageResponse.class);
        }

        List<Event> items = eventRepository.search(
                request.getType(), request.getFrom(), request.getTo(),
                null, request.getQ(), request.getCategory(), request.isAvailableOnly(),
                sortField, request.isDesc(), page, size);
        long total = eventRepository.countSearch(
                request.getType(), request.getFrom(), request.getTo(),
                null, request.getQ(), request.getCategory(), request.isAvailableOnly());

        PageResponse<Event> response = PageResponse.of(items, total, page, size);
        queryCache.put(cacheKey, couch.mapper().valueToTree(response));
        return response;
    }

    public JsonNode explain(String type, LocalDateTime from, LocalDateTime to) {
        return eventRepository.explain(type, from, to);
    }

    // ---------- migration / replication / analytics ----------

    public int migrate() {
        int migrated = migrationService.migrate();
        invalidateSearchCache();
        log.info("CouchDB migration finished: {} document(s) migrated", migrated);
        return migrated;
    }

    public void replicate() {
        RestClient replica = RestClient.builder()
                .baseUrl(couch.baseUrl())
                .defaultHeaders(h -> h.setBasicAuth(username, password))
                .build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", couch.database());
        body.put("target", replicaUrl + "/" + couch.database());
        body.put("create_target", true);
        replica.post().uri("/_replicate").body(body).retrieve().toBodilessEntity();
        log.info("Replication started: {} -> {}/{}", couch.database(), replicaUrl, couch.database());
    }

    public Map<String, Long> actionStatistics() {
        JsonNode rows = couch.view("analytics", "user_actions", Map.of("group", "true")).path("rows");
        Map<String, Long> result = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String action = row.path("key").path(1).asText("");
            if (!action.isBlank()) result.merge(action, row.path("value").asLong(), Long::sum);
        }
        return result;
    }

    private void invalidateSearchCache() {
        queryCache.invalidate();
    }
}