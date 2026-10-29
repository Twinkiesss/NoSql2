package ru.library.event.controller;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.library.event.model.CreateOrderRequest;
import ru.library.event.model.Order;
import ru.library.event.service.OrderService;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {
    private final OrderService orderService;
    public OrderController(OrderService orderService) { this.orderService = orderService; }

    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody CreateOrderRequest request, Authentication auth) {
        return ResponseEntity.ok(orderService.createOrder(request.getEventId(), auth.getName()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable String id, Authentication auth) {
        return orderService.getOrder(id, auth.getName(), isAdmin(auth))
                .map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders(Authentication auth) {
        return ResponseEntity.ok(orderService.getAllOrders(auth.getName(), isAdmin(auth)));
    }

    @GetMapping("/page")
    public ResponseEntity<Page<Order>> getOrdersPage(Authentication auth,
            @RequestParam(required = false) Order.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return ResponseEntity.ok(orderService.getOrdersPage(auth.getName(), isAdmin(auth), status,
                page, size, sort, direction));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Order> updateOrderStatus(@PathVariable String id,
            @RequestParam Order.Status status, Authentication auth) {
        return ResponseEntity.ok(orderService.updateOrderStatus(id, auth.getName(), status, isAdmin(auth)));
    }

    @PostMapping("/{id}/return")
    public ResponseEntity<Order> returnBook(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok(orderService.returnBook(id, auth.getName(), isAdmin(auth)));
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
