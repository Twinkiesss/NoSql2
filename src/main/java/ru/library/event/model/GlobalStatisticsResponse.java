package ru.library.event.model;

import java.util.List;

public record GlobalStatisticsResponse(
        long books,
        long orders,
        long issued,
        long returned,
        long readers,
        List<UserStatisticsResponse.TopViewedItem> topViewed
) {
}
