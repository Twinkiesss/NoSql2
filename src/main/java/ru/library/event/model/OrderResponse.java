package ru.library.event.model;

import java.time.LocalDateTime;

public record OrderResponse(
        String id,
        String eventId,
        String userId,
        String readerName,
        Order.Status status,
        String issuedById,
        String issuedByName,
        LocalDateTime createdAt,
        LocalDateTime issuedAt,
        LocalDateTime returnedAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getEventId(),
                order.getUserId(),
                order.getReaderName() == null ? order.getUserId() : order.getReaderName(),
                order.getStatus(),
                order.getManagerId(),
                order.getManagerName(),
                order.getCreatedAt(),
                order.getIssuedAt(),
                order.getReturnedAt()
        );
    }
}
