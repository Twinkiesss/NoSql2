package ru.library.event.repository;

public interface UserOrderStatsProjection {
    long getIssued();

    long getReturned();

    long getActive();
}
