package ru.library.event.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public class EventSearchRequest {
    private String type;
    private LocalDateTime from;
    private LocalDateTime to;

    @Size(max = 100)
    private String q;

    private String category;
    private boolean availableOnly;
    private EventSort sort = EventSort.UPDATED_AT;
    private boolean desc;

    @Min(0)
    private int page;

    @Min(1)
    @Max(100)
    private int size = 10;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public LocalDateTime getFrom() { return from; }
    public void setFrom(LocalDateTime from) { this.from = from; }
    public LocalDateTime getTo() { return to; }
    public void setTo(LocalDateTime to) { this.to = to; }
    public String getQ() { return q; }
    public void setQ(String q) { this.q = q; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public boolean isAvailableOnly() { return availableOnly; }
    public void setAvailableOnly(boolean availableOnly) { this.availableOnly = availableOnly; }
    public EventSort getSort() { return sort; }
    public void setSort(EventSort sort) { this.sort = sort == null ? EventSort.UPDATED_AT : sort; }
    public boolean isDesc() { return desc; }
    public void setDesc(boolean desc) { this.desc = desc; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
}
