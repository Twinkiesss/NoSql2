package ru.library.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.library.event.couch.CouchDbClient;
import ru.library.event.model.Order;
import ru.library.event.model.UserStatisticsResponse;
import ru.library.event.model.UserStatisticsResponse.OrderStats;
import ru.library.event.model.UserStatisticsResponse.TopViewedItem;
import ru.library.event.repository.OrderRepository;
import ru.library.event.repository.UserOrderStatsProjection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class UserStatisticsService {
    private static final int TOP_VIEWED_LIMIT = 5;
    private static final String CACHE_PREFIX = "stats:user:";

    private final OrderRepository orderRepository;
    private final CouchDbClient couch;
    private final EtcdQueryCacheService cache;

    public UserStatisticsService(OrderRepository orderRepository,
                                 CouchDbClient couch,
                                 EtcdQueryCacheService cache) {
        this.orderRepository = orderRepository;
        this.couch = couch;
        this.cache = cache;
    }

    public UserStatisticsResponse getStatistics(String userId, boolean refresh) {
        String cacheKey = CACHE_PREFIX + userId;
        if (!refresh) {
            Optional<JsonNode> cached = cache.get(cacheKey);
            if (cached.isPresent()) {
                return cache.mapper().convertValue(cached.get(), UserStatisticsResponse.class);
            }
        }

        UserStatisticsResponse response = new UserStatisticsResponse(
                userId,
                buildOrderStats(userId),
                Map.of(),
                buildTopViewed(userId));

        cache.put(cacheKey, cache.mapper().valueToTree(response));
        return response;
    }

    private OrderStats buildOrderStats(String userId) {
        UserOrderStatsProjection stats = orderRepository.findUserStats(userId, Order.Status.CONFIRMED);
        long issued = stats.getIssued();
        long returned = stats.getReturned();
        int returnRate = issued == 0 ? 0 : (int) Math.round(returned * 100.0 / issued);
        return new OrderStats(issued, returned, returnRate, stats.getActive());
    }

    private List<TopViewedItem> buildTopViewed(String userId) {
        String startKey = couch.mapper().valueToTree(List.of(userId, "")).toString();
        String endKey = couch.mapper().valueToTree(List.of(userId, Map.of())).toString();
        JsonNode rows = couch.view("analytics", "user_views", Map.of(
                "startkey", startKey,
                "endkey", endKey,
                "group", "true")).path("rows");

        Map<String, Long> viewsByEvent = new HashMap<>();
        for (JsonNode row : rows) {
            String eventId = row.path("key").path(1).asText("");
            if (eventId.isBlank()) continue;
            viewsByEvent.merge(eventId, row.path("value").asLong(), Long::sum);
        }

        List<Map.Entry<String, Long>> top = viewsByEvent.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_VIEWED_LIMIT)
                .toList();
        if (top.isEmpty()) return List.of();

        List<String> ids = top.stream().map(Map.Entry::getKey).toList();
        JsonNode docs = couch.find(Map.of("_id", Map.of("$in", ids)),
                List.of(), ids.size(), 0, "_id,title");
        Map<String, String> titles = new HashMap<>();
        docs.path("docs").forEach(doc -> {
            String id = doc.path("_id").asText();
            titles.put(id, doc.path("title").asText(id));
        });

        List<TopViewedItem> items = new ArrayList<>();
        for (Map.Entry<String, Long> entry : top) {
            String title = titles.get(entry.getKey());
            if (title == null) continue;
            items.add(new TopViewedItem(entry.getKey(), title, entry.getValue()));
        }
        return items;
    }
}