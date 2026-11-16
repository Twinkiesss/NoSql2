package ru.library.event.scylla;

import java.time.Instant;
import java.time.LocalDate;

public record ActionEvent(
        String eventId, String userId, String action, String objectId,
        Instant eventTime, LocalDate bucketDate, int objectHash
) {}
