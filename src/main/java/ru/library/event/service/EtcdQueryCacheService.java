package ru.library.event.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.options.PutOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@Service
public class EtcdQueryCacheService {
    private static final String GENERATION_KEY = "couch-cache:generation";
    private static final String PREFIX = "couch-cache:";
    private final KV kv;
    private final Lease lease;
    private final ObjectMapper mapper;
    private final long ttlSeconds;

    public EtcdQueryCacheService(KV kv, Lease lease, ObjectMapper mapper,
                                  @Value("${couchdb.cache-ttl-seconds:60}") long ttlSeconds) {
        this.kv = kv;
        this.lease = lease;
        this.mapper = mapper;
        this.ttlSeconds = ttlSeconds;
    }

    public Optional<JsonNode> get(String query) {
        try {
            long generation = generation();
            String key = key(generation, query);
            var response = kv.get(bs(key)).get();
            if (response.getKvs().isEmpty()) return Optional.empty();
            return Optional.of(mapper.readTree(response.getKvs().get(0).getValue().getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException("Etcd cache read failed", e);
        }
    }

    public void put(String query, JsonNode value) {
        try {
            long generation = generation();
            long leaseId = lease.grant(ttlSeconds).get().getID();
            kv.put(bs(key(generation, query)),
                    bs(value.toString()),
                    PutOption.newBuilder().withLeaseId(leaseId).build()).get();
        } catch (Exception e) {
            throw new IllegalStateException("Etcd cache write failed", e);
        }
    }

    public void invalidate() {
        try {
            long current = generation();
            kv.put(bs(GENERATION_KEY), bs(Long.toString(current + 1))).get();
        } catch (Exception e) {
            throw new IllegalStateException("Etcd cache invalidation failed", e);
        }
    }

    private long generation() throws ExecutionException, InterruptedException {
        var response = kv.get(bs(GENERATION_KEY)).get();
        if (response.getKvs().isEmpty()) {
            kv.put(bs(GENERATION_KEY), bs("1")).get();
            return 1;
        }
        return Long.parseLong(response.getKvs().get(0).getValue().toString(StandardCharsets.UTF_8));
    }

    private static ByteSequence bs(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }

    private static String key(long generation, String query) {
        return PREFIX + generation + ":" + sha256(query);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
