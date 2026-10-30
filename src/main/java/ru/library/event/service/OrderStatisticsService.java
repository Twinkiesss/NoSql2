package ru.library.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.library.event.model.Order;
import ru.library.event.repository.OrderRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class OrderStatisticsService {
    private static final String CACHE_KEY = "stats:orders:global";

    private final OrderRepository repository;
    private final EtcdQueryCacheService cache;

    public OrderStatisticsService(OrderRepository repository, EtcdQueryCacheService cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStatistics(boolean refresh) {
        if (!refresh) {
            Optional<JsonNode> cached = cache.get(CACHE_KEY);
            if (cached.isPresent()) {
                return cache.mapper().convertValue(cached.get(), Map.class);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", repository.count());

        Map<String, Long> statuses = new LinkedHashMap<>();
        for (Order.Status status : Order.Status.values()) {
            statuses.put(status.name(), repository.countByStatus(status));
        }
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

        cache.put(CACHE_KEY, cache.mapper().valueToTree(result));
        return result;
    }
}