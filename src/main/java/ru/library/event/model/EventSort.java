package ru.library.event.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EventSort {
    TITLE("title"),
    AUTHOR("author"),
    CATEGORY("category"),
    AVAILABLE_COPIES("availableCopies"),
    VIEW_COUNT("viewCount"),
    CREATED_AT("createdAt"),
    UPDATED_AT("updatedAt");

    private final String field;

    EventSort(String field) {
        this.field = field;
    }

    @JsonValue
    public String getField() {
        return field;
    }
}
