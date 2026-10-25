package ru.library.event.service;

import org.springframework.stereotype.Service;
import ru.library.event.model.Event;
import ru.library.event.model.GlobalStatisticsResponse;
import ru.library.event.model.UserStatisticsResponse.TopViewedItem;
import ru.library.event.repository.LibraryStatsProjection;
import ru.library.event.repository.OrderRepository;

import java.util.Comparator;
import java.util.List;

@Service
public class GlobalStatisticsService {
    private static final int TOP_VIEWED_LIMIT = 5;

    private final EventService eventService;
    private final OrderRepository orderRepository;

    public GlobalStatisticsService(EventService eventService, OrderRepository orderRepository) {
        this.eventService = eventService;
        this.orderRepository = orderRepository;
    }

    public GlobalStatisticsResponse getStatistics() {
        List<Event> events = eventService.getAllEvents();
        LibraryStatsProjection stats = orderRepository.findLibraryStats();
        return new GlobalStatisticsResponse(
                events.size(),
                stats.getTotal(),
                stats.getIssued(),
                stats.getReturned(),
                stats.getReaders(),
                buildTopViewed(events));
    }

    /**
     * Топ-5 берётся из уже загруженных книг: viewCount — основной счётчик просмотров.
     */
    private List<TopViewedItem> buildTopViewed(List<Event> events) {
        return events.stream()
                .filter(event -> event.getViewCount() > 0)
                .sorted(Comparator.comparingLong(Event::getViewCount).reversed())
                .limit(TOP_VIEWED_LIMIT)
                .map(event -> new TopViewedItem(event.getId(), event.getTitle(), event.getViewCount()))
                .toList();
    }
}
