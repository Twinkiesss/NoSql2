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

@Service
public class UserStatisticsService {
    private static final int TOP_VIEWED_LIMIT = 5;

    private final OrderRepository orderRepository;
    private final CouchDbClient couch;

    public UserStatisticsService(OrderRepository orderRepository, CouchDbClient couch) {
        this.orderRepository = orderRepository;
        this.couch = couch;
    }

    public UserStatisticsResponse getStatistics(String userId) {
        return new UserStatisticsResponse(userId,
                buildOrderStats(userId),
                Map.of(),
                buildTopViewed(userId));
    }

    private OrderStats buildOrderStats(String userId) {
        UserOrderStatsProjection stats = orderRepository.findUserStats(userId, Order.Status.CONFIRMED);
        long issued = stats.getIssued();
        long returned = stats.getReturned();
        int returnRate = issued == 0 ? 0 : (int) Math.round(returned * 100.0 / issued);
        return new OrderStats(issued, returned, returnRate, stats.getActive());
    }

    /**
     * Просмотры считаются вьюхой CouchDB, названия книг забираются одним batch-запросом.
     */
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
