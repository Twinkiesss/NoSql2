package ru.library.event.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.library.event.model.CreateEventRequest;
import ru.library.event.model.Event;
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

    public EventController(EventService eventService, CouchDbRelatedObjectRepository relatedRepository) {
        this.eventService = eventService;
        this.relatedRepository = relatedRepository;
    }

    @PostMapping
    public ResponseEntity<Event> createEvent(@RequestBody CreateEventRequest request,
                                              Authentication authentication) {
        Event event = new Event(
                request.getTitle(), request.getDescription(), request.getAuthor(),
                request.getCategory(), request.getAvailableCopies());
        event.setType(request.getType());
        return ResponseEntity.ok(eventService.createEvent(event, authentication.getName()));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Event>> search(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "updatedAt") String sort,
            @RequestParam(defaultValue = "false") boolean desc,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return ResponseEntity.ok(eventService.search(type, from, to, state, sort, desc, safePage, safeSize));
    }

    @GetMapping("/search/explain")
    public ResponseEntity<?> explain(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to) {
        return ResponseEntity.ok(eventService.explain(type, from, to));
    }

    @GetMapping("/analytics/actions")
    public ResponseEntity<?> actionStatistics() {
        return ResponseEntity.ok(eventService.actionStatistics());
    }

    @PostMapping("/migrate")
    public ResponseEntity<?> migrate() {
        return ResponseEntity.ok(java.util.Map.of("migrated", eventService.migrate()));
    }

    @PostMapping("/replicate")
    public ResponseEntity<?> replicate() {
        eventService.replicate();
        return ResponseEntity.ok(java.util.Map.of("status", "replication started"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEvent(@PathVariable String id, Authentication authentication) {
        return eventService.getEvent(id, authentication.getName())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Event>> getAllEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Event> updateEvent(@PathVariable String id,
                                             @RequestBody CreateEventRequest request,
                                             Authentication authentication) {
        return eventService.getEvent(id)
                .map(existing -> {
                    existing.setTitle(request.getTitle());
                    existing.setDescription(request.getDescription());
                    existing.setAuthor(request.getAuthor());
                    existing.setCategory(request.getCategory());
                    existing.setType(request.getType());
                    existing.setAvailableCopies(request.getAvailableCopies());
                    return ResponseEntity.ok(eventService.updateEvent(existing, authentication.getName()));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEvent(@PathVariable String id, Authentication authentication) {
        eventService.deleteEvent(id, authentication.getName());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/views")
    public ResponseEntity<Long> getViewCount(@PathVariable String id) {
        return ResponseEntity.ok(eventService.getViewCount(id));
    }

    @PostMapping("/{id}/views")
    public ResponseEntity<Long> incrementViewCount(@PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok(eventService.incrementViewCount(id, authentication.getName()));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<Event>> getEventsByCategory(@PathVariable String category) {
        return ResponseEntity.ok(eventService.getEventsByCategory(category));
    }

    @GetMapping("/{id}/related")
    public ResponseEntity<List<RelatedObject>> getRelated(@PathVariable String id) {
        return ResponseEntity.ok(relatedRepository.findByEventId(id));
    }

    @PostMapping("/{id}/related")
    public ResponseEntity<RelatedObject> addRelated(@PathVariable String id,
                                                     @RequestBody RelatedObject object) {
        object.setEventId(id);
        object.setType("relatedObject");
        return ResponseEntity.ok(relatedRepository.save(object));
    }
}
