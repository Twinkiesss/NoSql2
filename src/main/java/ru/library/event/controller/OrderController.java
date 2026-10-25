package ru.library.event.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.library.event.model.CreateOrderRequest;
import ru.library.event.model.Order;
import ru.library.event.model.OrderResponse;
import ru.library.event.service.OrderService;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request,
                                                     Authentication authentication) {
        boolean admin = isAdmin(authentication);
        String readerId = admin ? request.getUserId() : authentication.getName();
        return ResponseEntity.ok(OrderResponse.from(orderService.createOrder(
                request.getEventId(), readerId, authentication.getName(), admin)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String id, Authentication authentication) {
        return orderService.getOrder(id, authentication.getName(), isAdmin(authentication))
                .map(order -> ResponseEntity.ok(OrderResponse.from(order)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getAllOrders(Authentication authentication) {
        return ResponseEntity.ok(orderService.getOrders(authentication.getName(), isAdmin(authentication)).stream()
                .map(OrderResponse::from)
                .toList());
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<OrderResponse> updateOrderStatus(@PathVariable String id,
                                                           @RequestParam Order.Status status,
                                                           Authentication authentication) {
        Order updated = orderService.updateOrderStatus(
                id, authentication.getName(), isAdmin(authentication), status);
        return ResponseEntity.ok(OrderResponse.from(updated));
    }

    @PostMapping("/{id}/return")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderResponse> returnBook(@PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok(OrderResponse.from(
                orderService.returnBook(id, authentication.getName(), true)));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
