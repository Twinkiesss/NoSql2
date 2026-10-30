package ru.library.event.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.library.event.model.CreateEventRequest;
import ru.library.event.model.Event;
import ru.library.event.model.EventSearchRequest;
import ru.library.event.model.PageResponse;
import ru.library.event.model.RelatedObject;
import ru.library.event.repository.CouchDbRelatedObjectRepository;
import ru.library.event.service.EventService;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@CrossOrigin(origins = "*")
public class EventController {
    private final EventService eventService;
    private final CouchDbRelatedObjectRepository relatedRepository;

    public EventController(EventService eventService,
                           CouchDbRelatedObjectRepository relatedRepository) {
        this.eventService = eventService;
        this.relatedRepository = relatedRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Event> createEvent(@Valid @RequestBody CreateEventRequest request,
                                             Authentication authentication) {
        Event event = fromRequest(request);
        event.setType(request.getType());
        return ResponseEntity.ok(eventService.createEvent(event, authentication.getName()));
    }

    @PostMapping("/search")
    public ResponseEntity<PageResponse<Event>> search(@Valid @RequestBody EventSearchRequest request) {
        return ResponseEntity.ok(eventService.searchPage(request));
    }

    @GetMapping("/search/explain")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> explain(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(eventService.explain(type, from, to));
    }

    @GetMapping("/analytics/actions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> actionStatistics() {
        return ResponseEntity.ok(eventService.actionStatistics());
    }

    @PostMapping("/migrate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> migrate() {
        return ResponseEntity.ok(java.util.Map.of("migrated", eventService.migrate()));
    }

    @PostMapping("/replicate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> replicate() {
        eventService.replicate();
        return ResponseEntity.ok(java.util.Map.of("status", "replication started"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEvent(@PathVariable String id) {
        return eventService.getEvent(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Event>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Event> updateEvent(@PathVariable String id,
                                             @Valid @RequestBody CreateEventRequest request,
                                             Authentication auth) {
        requireAdmin(auth);
        return eventService.getEvent(id).map(existing -> {
            existing.setTitle(request.getTitle());
            existing.setDescription(request.getDescription());
            existing.setAuthor(request.getAuthor());
            existing.setCategory(request.getCategory());
            existing.setAvailableCopies(request.getAvailableCopies());
            existing.setRating(request.getRating());
            existing.setGenres(request.getGenres());
            existing.setType(request.getType());
            return ResponseEntity.ok(eventService.updateEvent(existing, auth.getName()));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteEvent(@PathVariable String id, Authentication auth) {
        requireAdmin(auth);
        eventService.deleteEvent(id, auth.getName());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/views")
    public ResponseEntity<Long> getViewCount(@PathVariable String id) {
        return ResponseEntity.ok(eventService.getViewCount(id));
    }

    @PostMapping("/{id}/views")
    public ResponseEntity<Long> incrementViewCount(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(eventService.incrementViewCount(id, auth.getName()));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<Event>> getEventsByCategory(@PathVariable String category) {
        return ResponseEntity.ok(eventService.getEventsByCategory(category));
    }

    @PostMapping("/{id}/related")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RelatedObject> addRelated(@PathVariable String id,
                                                    @RequestBody RelatedObject object) {
        object.setEventId(id);
        object.setType("relatedObject");
        return ResponseEntity.ok(relatedRepository.save(object));
    }

    private Event fromRequest(CreateEventRequest request) {
        if (request.getRating() < 0 || request.getRating() > 5) {
            throw new IllegalArgumentException("Рейтинг должен быть от 0 до 5");
        }
        Event event = new Event(request.getTitle(), request.getDescription(), request.getAuthor(),
                request.getCategory(), request.getAvailableCopies());
        event.setRating(request.getRating());
        event.setGenres(request.getGenres());
        return event;
    }

    private static void requireAdmin(Authentication auth) {
        boolean admin = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (!admin) throw new AccessDeniedException("Только администратор может выполнить это действие");
    }
}