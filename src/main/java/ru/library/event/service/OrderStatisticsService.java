package ru.library.event.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.library.event.model.Order;
import ru.library.event.repository.OrderRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OrderStatisticsService {
    private static final long TTL_MILLIS = 30_000;
    private final OrderRepository repository;
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    public OrderStatisticsService(OrderRepository repository) { this.repository = repository; }

    @Transactional(readOnly = true)
    public synchronized Map<String, Object> getStatistics(boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && cached != null && now - cachedAt < TTL_MILLIS) return cached;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", repository.count());
        Map<String, Long> statuses = new LinkedHashMap<>();
        for (Order.Status status : Order.Status.values()) statuses.put(status.name(), repository.countByStatus(status));
        result.put("byStatus", statuses);
        List<Map<String, Object>> users = repository.countOrdersByUser().stream().limit(10).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", row[0]);
            item.put("count", row[1]);
            return item;
        }).toList();
        result.put("topUsers", users);
        result.put("generatedAt", Instant.now().toString());
        result.put("cacheTtlSeconds", 30);
        cached = Map.copyOf(result);
        cachedAt = now;
        return cached;
    }
}
