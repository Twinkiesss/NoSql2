package ru.library.event.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.library.event.scylla.ActionEvent;
import ru.library.event.scylla.ScyllaActionService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/action-history")
public class ActionHistoryController {
    private final ScyllaActionService service;

    public ActionHistoryController(ScyllaActionService service) {
        this.service = service;
    }

    @GetMapping("/object/{objectId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ActionEvent>> byObject(
            @PathVariable String objectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(service.objectEvents(objectId, from, to, page, Math.min(200, Math.max(1, size))));
    }

    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ActionEvent> byUser(@PathVariable String userId,
                                    @RequestParam LocalDate from,
                                    @RequestParam LocalDate to,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return service.userEvents(userId, from, to, page, Math.min(200, Math.max(1, size)));
    }

    @GetMapping("/type/{action}")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ActionEvent> byAction(@PathVariable String action,
                                      @RequestParam LocalDate from,
                                      @RequestParam LocalDate to,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "50") int size) {
        return service.actionEvents(action, from, to, page, Math.min(200, Math.max(1, size)));
    }

    @GetMapping("/health")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> health() {
        return Map.of("scyllaAvailable", service.available());
    }

    /**
     * Бенчмарк-эндпоинт: пишет N событий через тот же ScyllaActionService.record(...),
     * что и обычная работа приложения. objectHash считается в Java (Math.floorMod),
     * поэтому запись и чтение гарантированно попадают в одну партицию.
     */
    @PostMapping("/benchmark")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> benchmark(@RequestParam String objectId,
                                         @RequestParam(defaultValue = "200") int count) {
        int safeCount = Math.min(10000, Math.max(1, count));
        long start = System.nanoTime();
        for (int i = 0; i < safeCount; i++) {
            service.record("bench-user", "VIEW", objectId);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        return Map.of(
                "objectId", objectId,
                "written", safeCount,
                "elapsedMs", elapsedMs,
                "throughputOpsPerSec", elapsedMs == 0 ? safeCount : (safeCount * 1000L / elapsedMs)
        );
    }
}