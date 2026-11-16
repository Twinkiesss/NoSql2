package ru.library.event.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.library.event.model.CreateTemporaryRequest;
import ru.library.event.model.TemporaryRequest;
import ru.library.event.model.TemporaryRequestResponse;
import ru.library.event.service.OrderService;

import java.util.List;

@RestController
@RequestMapping("/api/temp-requests")
@CrossOrigin(origins = "*")
public class TemporaryRequestController {

    private final OrderService orderService;

    public TemporaryRequestController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TemporaryRequestResponse> createTemporaryRequest(
            @Valid @RequestBody CreateTemporaryRequest body,
            Authentication authentication) {
        TemporaryRequest request = orderService.createTemporaryRequest(
                body.getEventId(), body.getUserId(), authentication.getName(),
                body.getPurpose(), body.getTtlSeconds());
        return ResponseEntity.ok(TemporaryRequestResponse.from(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TemporaryRequestResponse> getTemporaryRequest(
            @PathVariable String id, Authentication authentication) {
        return orderService.getTemporaryRequest(id, authentication.getName(), isAdmin(authentication))
                .filter(request -> !request.isExpired())
                .map(request -> ResponseEntity.ok(TemporaryRequestResponse.from(request)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<TemporaryRequestResponse>> getAllTemporaryRequests(Authentication authentication) {
        return ResponseEntity.ok(orderService.getTemporaryRequests(
                        authentication.getName(), isAdmin(authentication)).stream()
                .map(TemporaryRequestResponse::from)
                .toList());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteTemporaryRequest(@PathVariable String id) {
        orderService.deleteTemporaryRequest(id);
        return ResponseEntity.ok().build();
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
