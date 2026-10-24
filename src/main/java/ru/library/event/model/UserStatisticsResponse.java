package ru.library.event.model;

import java.util.List;
import java.util.Map;

public class UserStatisticsResponse {
    private String userId;
    private OrderStats orders;
    private Map<String, Long> actions;
    private List<TopViewedItem> topViewed;

    public UserStatisticsResponse(String userId, OrderStats orders,
                                  Map<String, Long> actions, List<TopViewedItem> topViewed) {
        this.userId = userId;
        this.orders = orders;
        this.actions = actions;
        this.topViewed = topViewed;
    }

    public String getUserId() { return userId; }
    public OrderStats getOrders() { return orders; }
    public Map<String, Long> getActions() { return actions; }
    public List<TopViewedItem> getTopViewed() { return topViewed; }

    public static class OrderStats {
        private final long issued;
        private final long returned;
        private final int returnRatePercent;
        private final long active;

        public OrderStats(long issued, long returned, int returnRatePercent, long active) {
            this.issued = issued;
            this.returned = returned;
            this.returnRatePercent = returnRatePercent;
            this.active = active;
        }

        public long getIssued() { return issued; }
        public long getReturned() { return returned; }
        public int getReturnRatePercent() { return returnRatePercent; }
        public long getActive() { return active; }
    }

    public static class TopViewedItem {
        private final String eventId;
        private final String title;
        private final long viewCount;

        public TopViewedItem(String eventId, String title, long viewCount) {
            this.eventId = eventId;
            this.title = title;
            this.viewCount = viewCount;
        }

        public String getEventId() { return eventId; }
        public String getTitle() { return title; }
        public long getViewCount() { return viewCount; }
    }
}