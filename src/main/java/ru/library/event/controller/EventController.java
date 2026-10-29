package ru.library.event.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.library.event.model.CreateEventRequest;
import ru.library.event.model.Event;
import ru.library.event.service.EventService;

import java.util.List;

@RestController
@RequestMapping("/api/events")
@CrossOrigin(origins = "*")
public class EventController {
    private final EventService eventService;
    public EventController(EventService eventService) { this.eventService = eventService; }

    @PostMapping
    public ResponseEntity<Event> createEvent(@RequestBody CreateEventRequest request, Authentication auth) {
        requireAdmin(auth);
        Event event = fromRequest(request);
        return ResponseEntity.ok(eventService.createEvent(event));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEvent(@PathVariable String id) {
        return eventService.getEvent(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Event>> getAllEvents() { return ResponseEntity.ok(eventService.getAllEvents()); }

    @PutMapping("/{id}")
    public ResponseEntity<Event> updateEvent(@PathVariable String id, @RequestBody CreateEventRequest request,
                                              Authentication auth) {
        requireAdmin(auth);
        if (request.getRating() < 0 || request.getRating() > 5) throw new IllegalArgumentException("Рейтинг должен быть от 0 до 5");
        return eventService.getEvent(id).map(existing -> {
            existing.setTitle(request.getTitle());
            existing.setDescription(request.getDescription());
            existing.setAuthor(request.getAuthor());
            existing.setCategory(request.getCategory());
            existing.setAvailableCopies(request.getAvailableCopies());
            existing.setRating(request.getRating());
            existing.setGenres(request.getGenres());
            return ResponseEntity.ok(eventService.updateEvent(existing));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEvent(@PathVariable String id, Authentication auth) {
        requireAdmin(auth);
        eventService.deleteEvent(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/views")
    public ResponseEntity<Long> getViewCount(@PathVariable String id) { return ResponseEntity.ok(eventService.getViewCount(id)); }

    @PostMapping("/{id}/views")
    public ResponseEntity<Long> incrementViewCount(@PathVariable String id) { return ResponseEntity.ok(eventService.incrementViewCount(id)); }

    @GetMapping("/category/{category}")
    public ResponseEntity<List<Event>> getEventsByCategory(@PathVariable String category) {
        return ResponseEntity.ok(eventService.getEventsByCategory(category));
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

    private void requireAdmin(Authentication auth) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            throw new AccessDeniedException("Операция доступна только администратору");
        }
    }
}
