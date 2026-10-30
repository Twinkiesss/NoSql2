package ru.library.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import ru.library.event.model.Event;
import ru.library.event.model.GlobalStatisticsResponse;
import ru.library.event.model.UserStatisticsResponse.TopViewedItem;
import ru.library.event.repository.LibraryStatsProjection;
import ru.library.event.repository.OrderRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class GlobalStatisticsService {
    private static final String CACHE_KEY = "stats:global";
    private static final int TOP_VIEWED_LIMIT = 5;

    private final EventService eventService;
    private final OrderRepository orderRepository;
    private final EtcdQueryCacheService cache;

    public GlobalStatisticsService(EventService eventService,
                                   OrderRepository orderRepository,
                                   EtcdQueryCacheService cache) {
        this.eventService = eventService;
        this.orderRepository = orderRepository;
        this.cache = cache;
    }

    public GlobalStatisticsResponse getStatistics(boolean refresh) {
        if (!refresh) {
            Optional<JsonNode> cached = cache.get(CACHE_KEY);
            if (cached.isPresent()) {
                return cache.mapper().convertValue(cached.get(), GlobalStatisticsResponse.class);
            }
        }

        List<Event> events = eventService.getAllEvents();
        LibraryStatsProjection stats = orderRepository.findLibraryStats();
        GlobalStatisticsResponse response = new GlobalStatisticsResponse(
                events.size(),
                stats.getTotal(),
                stats.getIssued(),
                stats.getReturned(),
                stats.getReaders(),
                buildTopViewed(events));

        cache.put(CACHE_KEY, cache.mapper().valueToTree(response));
        return response;
    }

    private List<TopViewedItem> buildTopViewed(List<Event> events) {
        return events.stream()
                .filter(event -> event.getViewCount() > 0)
                .sorted(Comparator.comparingLong(Event::getViewCount).reversed())
                .limit(TOP_VIEWED_LIMIT)
                .map(event -> new TopViewedItem(event.getId(), event.getTitle(), event.getViewCount()))
                .toList();
    }
}