package ru.library.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.library.event.couch.CouchDbClient;
import ru.library.event.model.Order;
import ru.library.event.model.UserStatisticsResponse;
import ru.library.event.model.UserStatisticsResponse.OrderStats;
import ru.library.event.model.UserStatisticsResponse.TopViewedItem;
import ru.library.event.repository.OrderRepository;

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
        List<JsonNode> actions = findUserActions(userId);
        return new UserStatisticsResponse(userId,
                buildOrderStats(userId),
                buildActionStats(actions),
                buildTopViewed(actions));
    }

    private OrderStats buildOrderStats(String userId) {
        long issued = orderRepository.countByUserIdAndIssuedAtNotNull(userId);
        long returned = orderRepository.countByUserIdAndReturnedAtNotNull(userId);
        int returnRate = issued == 0 ? 0 : (int) Math.round(returned * 100.0 / issued);
        return new OrderStats(issued, returned, returnRate,
                orderRepository.countByUserIdAndStatus(userId, Order.Status.CONFIRMED));
    }

    private List<JsonNode> findUserActions(String userId) {
        JsonNode result = couch.find(Map.of("type", "action", "userId", userId),
                List.of(), 10000, 0, null);
        List<JsonNode> actions = new ArrayList<>();
        result.path("docs").forEach(actions::add);
        return actions;
    }

    private Map<String, Long> buildActionStats(List<JsonNode> actions) {
        Map<String, Long> byAction = new HashMap<>();
        for (JsonNode doc : actions) {
            String action = doc.path("action").asText("");
            if ("VIEW".equals(action) || "READ".equals(action)) continue;
            byAction.merge(action, 1L, Long::sum);
        }
        return byAction;
    }

    private List<TopViewedItem> buildTopViewed(List<JsonNode> actions) {
        Map<String, Long> viewsByEvent = new HashMap<>();
        for (JsonNode doc : actions) {
            if ("VIEW".equals(doc.path("action").asText())) {
                viewsByEvent.merge(doc.path("eventId").asText(""), 1L, Long::sum);
            }
        }
        List<Map.Entry<String, Long>> top = viewsByEvent.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(TOP_VIEWED_LIMIT)
                .toList();
        List<TopViewedItem> items = new ArrayList<>();
        for (Map.Entry<String, Long> entry : top) {
            JsonNode event = couch.getOrNull("/" + couch.database() + "/" + entry.getKey());
            if (event == null || event.has("_deleted") || event.has("error")) continue;
            items.add(new TopViewedItem(entry.getKey(),
                    event.path("title").asText(entry.getKey()), entry.getValue()));
        }
        return items;
    }
}