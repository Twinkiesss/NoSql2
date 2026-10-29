package ru.library.event.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {
    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;
    @Column(nullable = false) private String title;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(nullable = false) private String author;
    @Column(nullable = false) private String category;
    @Column(nullable = false) private int availableCopies;
    @Column(nullable = false) private long viewCount;
    @Column(nullable = false, columnDefinition = "double precision default 0") private double rating;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "event_genres", joinColumns = @JoinColumn(name = "event_id"))
    @Column(name = "genre", nullable = false, length = 80)
    private List<String> genres = new ArrayList<>();
    @Column(nullable = false, columnDefinition = "integer default 2") private int schemaVersion = 2;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;
    @Column private LocalDateTime lastChangedAt;

    public Event() {
        this.id = UUID.randomUUID().toString();
        this.viewCount = 0;
        this.rating = 0.0;
        this.schemaVersion = 2;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.lastChangedAt = this.updatedAt;
    }

    public Event(String title, String description, String author, String category, int availableCopies) {
        this();
        this.title = title;
        this.description = description;
        this.author = author;
        this.category = category;
        this.availableCopies = availableCopies;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; touch(); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; touch(); }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; touch(); }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; touch(); }
    public int getAvailableCopies() { return availableCopies; }
    public void setAvailableCopies(int availableCopies) { this.availableCopies = availableCopies; touch(); }
    public long getViewCount() { return viewCount; }
    public void setViewCount(long viewCount) { this.viewCount = viewCount; }
    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; touch(); }
    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres.clear(); if (genres != null) this.genres.addAll(genres); touch(); }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getLastChangedAt() { return lastChangedAt == null ? updatedAt : lastChangedAt; }
    public void setLastChangedAt(LocalDateTime lastChangedAt) { this.lastChangedAt = lastChangedAt; }

    private void touch() {
        this.updatedAt = LocalDateTime.now();
        this.lastChangedAt = this.updatedAt;
        this.schemaVersion = 2;
    }
}
