package ru.library.event.model;

import java.util.List;

public class CreateEventRequest {
    private String type = "book";
    private String title;
    private String description;
    private String author;
    private String category;
    private int availableCopies;
    private double rating;
    private List<String> genres;

    public String getType() { return type; }
    public void setType(String type) { this.type = type == null || type.isBlank() ? "book" : type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getAvailableCopies() { return availableCopies; }
    public void setAvailableCopies(int availableCopies) { this.availableCopies = availableCopies; }
    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }
    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres; }
}