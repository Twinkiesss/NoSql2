package ru.library.event.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.UUID;

public class TemporaryRequest {
    private String id;
    private String eventId;
    private String userId;
    private String userName;
    private String createdById;
    private String createdByName;
    private String purpose;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private boolean active;

    public TemporaryRequest() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now();
        this.active = true;
    }

    public TemporaryRequest(String eventId, String userId, String userName,
                            String createdById, String createdByName,
                            String purpose, long ttlSeconds) {
        this();
        this.eventId = eventId;
        this.userId = userId;
        this.userName = userName;
        this.createdById = createdById;
        this.createdByName = createdByName;
        this.purpose = purpose;
        this.expiresAt = this.createdAt.plusSeconds(ttlSeconds);
    }

    @JsonIgnore
    public boolean isExpired() {
        return expiresAt == null || LocalDateTime.now().isAfter(expiresAt);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getCreatedById() { return createdById; }
    public void setCreatedById(String createdById) { this.createdById = createdById; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
