package ru.library.event.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.library.event.service.OrderStatisticsService;

import java.util.Map;

@RestController
@RequestMapping("/api/statistics/orders")
public class OrderStatisticsController {
    private final OrderStatisticsService statisticsService;
    public OrderStatisticsController(OrderStatisticsService statisticsService) { this.statisticsService = statisticsService; }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getStatistics(Authentication auth,
            @RequestParam(defaultValue = "false") boolean refresh) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin) throw new AccessDeniedException("Статистика доступна только администратору");
        return ResponseEntity.ok(statisticsService.getStatistics(refresh));
    }
}
