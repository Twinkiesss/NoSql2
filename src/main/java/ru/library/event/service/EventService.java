package ru.library.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.library.event.couch.CouchDbClient;
import ru.library.event.exception.EventNotFoundException;
import ru.library.event.model.Event;
import ru.library.event.repository.CouchDbEventRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final CouchDbEventRepository repository;
    private final CouchDbActionService actionService;
    private final EtcdQueryCacheService queryCache;
    private final CouchDbClient couch;
    private final ObjectMapper mapper;
    private final CouchDbMigrationService migrationService;

    public EventService(CouchDbEventRepository repository,
                        CouchDbActionService actionService,
                        EtcdQueryCacheService queryCache,
                        CouchDbClient couch,
                        ObjectMapper mapper,
                        CouchDbMigrationService migrationService) {
        this.repository = repository;
        this.actionService = actionService;
        this.queryCache = queryCache;
        this.couch = couch;
        this.mapper = mapper;
        this.migrationService = migrationService;
    }

    public Event createEvent(Event event) {
        return createEvent(event, "system");
    }

    public Event createEvent(Event event, String userId) {
        LocalDateTime now = LocalDateTime.now();
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        event.setSchemaVersion(2);
        if (event.getType() == null || event.getType().isBlank()) event.setType("book");
        event.setLastChange(new Event.LastChange("CREATE", userId, now));
        event.getStateHistory().add(new Event.StateHistoryEntry("CREATED", "CREATE", userId, now));
        Event saved = repository.save(event);
        actionService.log(userId, "CREATE", saved.getId());
        queryCache.invalidate();
        return saved;
    }

    public Optional<Event> getEvent(String id) {
        return getEvent(id, "system");
    }

    public Optional<Event> getEvent(String id, String userId) {
        Event event = repository.findById(id);
        if (event != null) {
            actionService.log(userId, "READ", id);
            return Optional.of(event);
        }
        return Optional.empty();
    }

    public List<Event> getAllEvents() {
        return repository.findAll();
    }

    public Event updateEvent(Event input) {
        return updateEvent(input, "system");
    }

    public Event updateEvent(Event input, String userId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Event current = repository.findById(input.getId());
            if (current == null) throw new EventNotFoundException(input.getId());

            current.setTitle(input.getTitle());
            current.setDescription(input.getDescription());
            current.setAuthor(input.getAuthor());
            current.setCategory(input.getCategory());
            current.setType(input.getType() == null ? current.getType() : input.getType());
            current.setAvailableCopies(input.getAvailableCopies());

            LocalDateTime now = LocalDateTime.now();
            current.setUpdatedAt(now);
            current.setSchemaVersion(2);
            current.setLastChange(new Event.LastChange("UPDATE", userId, now));
            current.getStateHistory().add(new Event.StateHistoryEntry("UPDATED", "UPDATE", userId, now));

            try {
                Event saved = repository.saveWithRevision(current);
                actionService.log(userId, "UPDATE", saved.getId());
                queryCache.invalidate();
                return saved;
            } catch (CouchDbEventRepository.ConflictException conflict) {
                log.warn("CouchDB revision conflict for {}, retry {}", input.getId(), attempt + 1);
            }
        }
        throw new IllegalStateException("Не удалось обновить документ из-за конфликта ревизий: " + input.getId());
    }

    public void deleteEvent(String id) {
        deleteEvent(id, "system");
    }

    public void deleteEvent(String id, String userId) {
        Event event = repository.findById(id);
        if (event == null) return;
        try {
            repository.delete(id, event.getRev());
        } catch (org.springframework.web.client.RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new IllegalStateException("Конфликт _rev при удалении документа " + id);
            }
            throw e;
        }
        actionService.log(userId, "DELETE", id);
        queryCache.invalidate();
    }

    public long incrementViewCount(String eventId) {
        return incrementViewCount(eventId, "system");
    }

    public long incrementViewCount(String eventId, String userId) {
        for (int attempt = 0; attempt < 8; attempt++) {
            Event event = repository.findById(eventId);
            if (event == null) throw new EventNotFoundException(eventId);
            event.setViewCount(event.getViewCount() + 1);
            LocalDateTime now = LocalDateTime.now();
            event.setUpdatedAt(now);
            event.setLastChange(new Event.LastChange("VIEW", userId, now));
            event.getStateHistory().add(new Event.StateHistoryEntry("VIEWED", "VIEW", userId, now));
            try {
                Event saved = repository.saveWithRevision(event);
                actionService.log(userId, "VIEW", eventId);
                queryCache.invalidate();
                return saved.getViewCount();
            } catch (CouchDbEventRepository.ConflictException conflict) {
                log.debug("View counter conflict for {}, retry {}", eventId, attempt + 1);
            }
        }
        throw new IllegalStateException("Не удалось увеличить счётчик просмотров из-за конфликтов _rev");
    }

    public long getViewCount(String eventId) {
        Event event = repository.findById(eventId);
        return event == null ? 0L : event.getViewCount();
    }

    public int reserveOneCopy(String eventId) {
        return changeCopies(eventId, -1);
    }

    public int releaseOneCopy(String eventId) {
        return changeCopies(eventId, 1);
    }

    private int changeCopies(String eventId, int delta) {
        for (int attempt = 0; attempt < 8; attempt++) {
            Event event = repository.findById(eventId);
            if (event == null) return 0;
            if (delta < 0 && event.getAvailableCopies() <= 0) return 0;
            event.setAvailableCopies(event.getAvailableCopies() + delta);
            LocalDateTime now = LocalDateTime.now();
            event.setUpdatedAt(now);
            event.setLastChange(new Event.LastChange(
                    delta < 0 ? "RESERVE_COPY" : "RELEASE_COPY", "system", now));
            event.getStateHistory().add(new Event.StateHistoryEntry(
                    delta < 0 ? "COPY_RESERVED" : "COPY_RELEASED",
                    delta < 0 ? "RESERVE_COPY" : "RELEASE_COPY", "system", now));
            try {
                Event saved = repository.saveWithRevision(event);
                queryCache.invalidate();
                return saved.getAvailableCopies() >= 0 ? 1 : 0;
            } catch (CouchDbEventRepository.ConflictException conflict) {
                log.debug("Copy counter conflict for {}, retry {}", eventId, attempt + 1);
            }
        }
        return 0;
    }

    public List<Event> getEventsByCategory(String category) {
        return repository.findByCategory(category);
    }

    public List<Event> search(String type, LocalDateTime from, LocalDateTime to,
                              String nestedState, String sort, boolean desc, int page, int size) {
        String key = String.join("|",
                String.valueOf(type), String.valueOf(from), String.valueOf(to),
                String.valueOf(nestedState), String.valueOf(sort), String.valueOf(desc),
                String.valueOf(page), String.valueOf(size));
        Optional<JsonNode> cached = queryCache.get(key);
        if (cached.isPresent()) {
            return mapper.convertValue(cached.get(),
                    mapper.getTypeFactory().constructCollectionType(List.class, Event.class));
        }
        List<Event> result = repository.search(type, from, to, nestedState, sort, desc, page, size);
        queryCache.put(key, mapper.valueToTree(result));
        return result;
    }

    public JsonNode explain(String type, LocalDateTime from, LocalDateTime to) {
        return repository.explain(type, from, to);
    }

    public int migrate() {
        int result = migrationService.migrate();
        queryCache.invalidate();
        return result;
    }

    public JsonNode actionStatistics() {
        return couch.get("/" + couch.database() + "/_design/analytics/_view/user_actions?group=true");
    }

    public void replicate() {
        couch.post("/_replicate", java.util.Map.of(
                "source", couch.database(),
                "target", "http://admin:admin@couchdb2:5984/" + couch.database(),
                "continuous", true
        ));
    }

    public List<Event> relatedSearch(String nestedState) {
        return repository.search(null, null, null, nestedState, "updatedAt", false, 0, 100);
    }
}
