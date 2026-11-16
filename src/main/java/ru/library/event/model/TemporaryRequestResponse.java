package ru.library.event.model;

import java.time.LocalDateTime;

public record TemporaryRequestResponse(
        String id,
        String eventId,
        String userId,
        String userName,
        String createdById,
        String createdByName,
        String purpose,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        boolean active
) {
    public static TemporaryRequestResponse from(TemporaryRequest request) {
        return new TemporaryRequestResponse(
                request.getId(),
                request.getEventId(),
                request.getUserId(),
                request.getUserName() == null ? request.getUserId() : request.getUserName(),
                request.getCreatedById(),
                request.getCreatedByName(),
                request.getPurpose(),
                request.getCreatedAt(),
                request.getExpiresAt(),
                request.isActive()
        );
    }
}
