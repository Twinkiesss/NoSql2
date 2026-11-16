package ru.library.event.scylla;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Repository
public class ScyllaActionStore {

    private static final Logger log =
            LoggerFactory.getLogger(ScyllaActionStore.class);

    private final String host;
    private final String datacenter;
    private final String keyspace;
    private final String consistencyName;

    private final int port;
    private final int bucketCount;
    private final int pageSize;

    private CqlSession session;
    private volatile boolean available;

    private PreparedStatement insertObject;
    private PreparedStatement insertUser;
    private PreparedStatement insertType;

    public ScyllaActionStore(
            @Value("${scylla.contact-point:localhost}") String host,
            @Value("${scylla.port:9042}") int port,
            @Value("${scylla.datacenter:datacenter1}") String datacenter,
            @Value("${scylla.keyspace:library_events}") String keyspace,
            @Value("${scylla.bucket-count:16}") int bucketCount,
            @Value("${scylla.page-size:100}") int pageSize,
            @Value("${scylla.consistency:QUORUM}") String consistencyName
    ) {
        this.host = host;
        this.port = port;
        this.datacenter = datacenter;
        this.keyspace = keyspace;
        this.bucketCount = Math.max(1, bucketCount);
        this.pageSize = Math.max(1, pageSize);
        this.consistencyName = consistencyName;
    }

    @PostConstruct
    public void connect() {
        try {
            session = CqlSession.builder()
                    .addContactPoint(new InetSocketAddress(host, port))
                    .withLocalDatacenter(datacenter)
                    .build();

            session.execute(
                    "CREATE KEYSPACE IF NOT EXISTS " + keyspace +
                            " WITH replication = " +
                            "{'class':'NetworkTopologyStrategy','datacenter1':3}"
            );

            session.execute(
                    "CREATE TABLE IF NOT EXISTS " + keyspace +
                            ".action_by_object_bucket (" +
                            "bucket_date date, " +
                            "object_hash int, " +
                            "object_id text, " +
                            "event_time timestamp, " +
                            "event_id text, " +
                            "user_id text, " +
                            "action text, " +
                            "PRIMARY KEY ((bucket_date, object_hash, object_id), " +
                            "event_time, event_id)) " +
                            "WITH CLUSTERING ORDER BY " +
                            "(event_time DESC, event_id ASC)"
            );

            session.execute(
                    "CREATE TABLE IF NOT EXISTS " + keyspace +
                            ".action_by_user_bucket (" +
                            "bucket_date date, " +
                            "user_id text, " +
                            "user_hash int, " +
                            "event_time timestamp, " +
                            "event_id text, " +
                            "object_id text, " +
                            "action text, " +
                            "PRIMARY KEY ((bucket_date, user_hash, user_id), " +
                            "event_time, event_id)) " +
                            "WITH CLUSTERING ORDER BY " +
                            "(event_time DESC, event_id ASC)"
            );

            session.execute(
                    "CREATE TABLE IF NOT EXISTS " + keyspace +
                            ".action_by_type_bucket (" +
                            "bucket_date date, " +
                            "action text, " +
                            "action_hash int, " +
                            "event_time timestamp, " +
                            "event_id text, " +
                            "user_id text, " +
                            "object_id text, " +
                            "PRIMARY KEY ((bucket_date, action_hash, action), " +
                            "event_time, event_id)) " +
                            "WITH CLUSTERING ORDER BY " +
                            "(event_time DESC, event_id ASC)"
            );

            insertObject = session.prepare(
                    "INSERT INTO " + keyspace +
                            ".action_by_object_bucket " +
                            "(bucket_date, object_hash, object_id, " +
                            "event_time, event_id, user_id, action) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)"
            );

            insertUser = session.prepare(
                    "INSERT INTO " + keyspace +
                            ".action_by_user_bucket " +
                            "(bucket_date, user_id, user_hash, " +
                            "event_time, event_id, object_id, action) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)"
            );

            insertType = session.prepare(
                    "INSERT INTO " + keyspace +
                            ".action_by_type_bucket " +
                            "(bucket_date, action, action_hash, " +
                            "event_time, event_id, user_id, object_id) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)"
            );

            available = true;

            log.info(
                    "Connected to ScyllaDB at {}:{}; keyspace={}",
                    host,
                    port,
                    keyspace
            );

        } catch (Exception e) {
            available = false;

            log.error(
                    "ScyllaDB is unavailable; existing CRUD remains operational.",
                    e
            );

            if (session != null) {
                try {
                    session.close();
                } catch (Exception ignored) {
                    // Ignore closing errors.
                }

                session = null;
            }
        }
    }

    public boolean isAvailable() {
        return available && session != null;
    }

    public int bucketCount() {
        return bucketCount;
    }

    public void append(ActionEvent event) {
        if (!isAvailable()) {
            return;
        }

        try {
            int userHash = Math.floorMod(
                    event.userId().hashCode(),
                    bucketCount
            );

            int actionHash = Math.floorMod(
                    event.action().hashCode(),
                    bucketCount
            );

            DefaultConsistencyLevel consistency =
                    DefaultConsistencyLevel.valueOf(consistencyName);

            BoundStatement objectStatement = insertObject.bind(
                    event.bucketDate(),
                    event.objectHash(),
                    event.objectId(),
                    event.eventTime(),
                    event.eventId(),
                    event.userId(),
                    event.action()
            ).setConsistencyLevel(consistency);

            BoundStatement userStatement = insertUser.bind(
                    event.bucketDate(),
                    event.userId(),
                    userHash,
                    event.eventTime(),
                    event.eventId(),
                    event.objectId(),
                    event.action()
            ).setConsistencyLevel(consistency);

            BoundStatement typeStatement = insertType.bind(
                    event.bucketDate(),
                    event.action(),
                    actionHash,
                    event.eventTime(),
                    event.eventId(),
                    event.userId(),
                    event.objectId()
            ).setConsistencyLevel(consistency);

            session.execute(objectStatement);
            session.execute(userStatement);
            session.execute(typeStatement);

            log.debug(
                    "Action {} persisted in ScyllaDB",
                    event.eventId()
            );

        } catch (Exception e) {
            log.error("Could not persist action in ScyllaDB", e);
        }
    }

    public List<ActionEvent> findObjectEvents(
            String objectId,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        requireAvailable();

        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "'from' must not be after 'to'"
            );
        }

        int hash = Math.floorMod(
                objectId.hashCode(),
                bucketCount
        );

        List<ActionEvent> result = new ArrayList<>();

        LocalDate day = from.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate lastDay = to.atZone(ZoneOffset.UTC).toLocalDate();

        while (!day.isAfter(lastDay)) {
            SimpleStatement query = SimpleStatement.builder(
                            "SELECT * FROM " + keyspace +
                                    ".action_by_object_bucket " +
                                    "WHERE bucket_date = ? " +
                                    "AND object_hash = ? " +
                                    "AND object_id = ? " +
                                    "AND event_time >= ? " +
                                    "AND event_time <= ?"
                    )
                    .addPositionalValues(
                            day,
                            hash,
                            objectId,
                            from,
                            to
                    )
                    .setPageSize(pageSize)
                    .setConsistencyLevel(
                            DefaultConsistencyLevel.valueOf(consistencyName)
                    )
                    .build();

            for (Row row : session.execute(query)) {
                result.add(toEvent(row, day, objectId));
            }

            day = day.plusDays(1);
        }

        return paginate(result, page, size);
    }

    public List<ActionEvent> findUserEvents(
            String userId,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        requireAvailable();

        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "'from' must not be after 'to'"
            );
        }

        List<ActionEvent> result = new ArrayList<>();

        int hash = Math.floorMod(
                userId.hashCode(),
                bucketCount
        );

        for (
                LocalDate day = from;
                !day.isAfter(to);
                day = day.plusDays(1)
        ) {
            SimpleStatement query = SimpleStatement.builder(
                            "SELECT * FROM " + keyspace +
                                    ".action_by_user_bucket " +
                                    "WHERE bucket_date = ? " +
                                    "AND user_hash = ? " +
                                    "AND user_id = ?"
                    )
                    .addPositionalValues(day, hash, userId)
                    .setPageSize(pageSize)
                    .setConsistencyLevel(
                            DefaultConsistencyLevel.valueOf(consistencyName)
                    )
                    .build();

            for (Row row : session.execute(query)) {
                String objectId = row.getString("object_id");

                result.add(
                        new ActionEvent(
                                row.getString("event_id"),
                                userId,
                                row.getString("action"),
                                objectId,
                                row.getInstant("event_time"),
                                day,
                                Math.floorMod(
                                        objectId.hashCode(),
                                        bucketCount
                                )
                        )
                );
            }
        }

        return paginate(result, page, size);
    }

    public List<ActionEvent> findActionEvents(
            String action,
            LocalDate from,
            LocalDate to,
            int page,
            int size
    ) {
        requireAvailable();

        if (from.isAfter(to)) {
            throw new IllegalArgumentException(
                    "'from' must not be after 'to'"
            );
        }

        List<ActionEvent> result = new ArrayList<>();

        int hash = Math.floorMod(
                action.hashCode(),
                bucketCount
        );

        for (
                LocalDate day = from;
                !day.isAfter(to);
                day = day.plusDays(1)
        ) {
            SimpleStatement query = SimpleStatement.builder(
                            "SELECT * FROM " + keyspace +
                                    ".action_by_type_bucket " +
                                    "WHERE bucket_date = ? " +
                                    "AND action_hash = ? " +
                                    "AND action = ?"
                    )
                    .addPositionalValues(day, hash, action)
                    .setPageSize(pageSize)
                    .setConsistencyLevel(
                            DefaultConsistencyLevel.valueOf(consistencyName)
                    )
                    .build();

            for (Row row : session.execute(query)) {
                String objectId = row.getString("object_id");

                result.add(
                        new ActionEvent(
                                row.getString("event_id"),
                                row.getString("user_id"),
                                action,
                                objectId,
                                row.getInstant("event_time"),
                                day,
                                Math.floorMod(
                                        objectId.hashCode(),
                                        bucketCount
                                )
                        )
                );
            }
        }

        return paginate(result, page, size);
    }

    private List<ActionEvent> paginate(
            List<ActionEvent> events,
            int page,
            int size
    ) {
        events.sort(
                Comparator.comparing(ActionEvent::eventTime)
                        .reversed()
                        .thenComparing(ActionEvent::eventId)
        );

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, size);

        long startLong = (long) safePage * safeSize;

        if (startLong >= events.size()) {
            return List.of();
        }

        int start = (int) startLong;
        int end = Math.min(events.size(), start + safeSize);

        return List.copyOf(events.subList(start, end));
    }

    private ActionEvent toEvent(
            Row row,
            LocalDate date,
            String objectId
    ) {
        return new ActionEvent(
                row.getString("event_id"),
                row.getString("user_id"),
                row.getString("action"),
                objectId,
                row.getInstant("event_time"),
                date,
                row.getInt("object_hash")
        );
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new IllegalStateException(
                    "ScyllaDB is unavailable. Start the ScyllaDB cluster first."
            );
        }
    }

    @PreDestroy
    public void close() {
        if (session != null) {
            session.close();
        }
    }
}
