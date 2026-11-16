package ru.library.event.repository;

public interface LibraryStatsProjection {
    long getTotal();

    long getIssued();

    long getReturned();

    long getReaders();
}
