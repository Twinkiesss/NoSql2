package ru.library.event.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Event {
    private String id;
    private String title;
    private String description;
    private String author;
    private String category;
    private String type;
    private int availableCopies;
    private long viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private int schemaVersion;
    private Long version;
    private LastChange lastChange;
    private List<StateHistoryEntry> stateHistory = new ArrayList<>();
    @JsonIgnore
    private String rev;

    public Event() {
        this.id = UUID.randomUUID().toString();
        this.viewCount = 0;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.schemaVersion = 2;
        this.version = 1L;
        this.type = "book";
    }

    public Event(String title, String description, String author, String category, int availableCopies) {
        this();
        this.title = title;
        this.description = description;
        this.author = author;
        this.category = category;
        this.availableCopies = availableCopies;
        this.lastChange = new LastChange("CREATE", "system", LocalDateTime.now());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public int getAvailableCopies() { return availableCopies; }
    public void setAvailableCopies(int availableCopies) { this.availableCopies = availableCopies; }
    public long getViewCount() { return viewCount; }
    public void setViewCount(long viewCount) { this.viewCount = viewCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public LastChange getLastChange() { return lastChange; }
    public void setLastChange(LastChange lastChange) { this.lastChange = lastChange; }
    public List<StateHistoryEntry> getStateHistory() { return stateHistory; }
    public void setStateHistory(List<StateHistoryEntry> stateHistory) {
        this.stateHistory = stateHistory == null ? new ArrayList<>() : stateHistory;
    }
    public String getRev() { return rev; }
    public void setRev(String rev) { this.rev = rev; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public record LastChange(String action, String userId, LocalDateTime changedAt) {}
    public record StateHistoryEntry(String state, String action, String userId, LocalDateTime changedAt) {}
}
