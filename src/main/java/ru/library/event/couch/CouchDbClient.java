package ru.library.event.couch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Component
public class CouchDbClient {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final String database;

    public CouchDbClient(@Value("${couchdb.primary-url}") String url,
                         @Value("${couchdb.username}") String username,
                         @Value("${couchdb.password}") String password,
                         @Value("${couchdb.database}") String database,
                         ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper;
        this.client = RestClient.builder()
                .baseUrl(url)
                .defaultHeaders(h -> h.setBasicAuth(username, password))
                .build();
    }

    public String database() {
        return database;
    }

    public JsonNode get(String path) {
        return client.get().uri(path).retrieve().body(JsonNode.class);
    }

    public JsonNode getOrNull(String path) {
        try {
            return get(path);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return null;
            throw e;
        }
    }

    public JsonNode put(String path, Object body) {
        return client.put().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JsonNode.class);
    }

    public JsonNode post(String path, Object body) {
        return client.post().uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(JsonNode.class);
    }

    public void delete(String path) {
        client.delete().uri(path).retrieve().toBodilessEntity();
    }

    public boolean exists(String path) {
        try {
            client.get().uri(path).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) return false;
            throw e;
        }
    }

    public JsonNode find(Map<String, Object> selector, Map<String, Object> sort,
                         int limit, int skip, String fields) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("selector", selector);
        if (sort != null && !sort.isEmpty()) body.put("sort", sort);
        body.put("limit", limit);
        body.put("skip", skip);
        if (fields != null && !fields.isBlank()) {
            body.put("fields", java.util.Arrays.stream(fields.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).toList());
        }
        return post("/" + database + "/_find", body);
    }

    public JsonNode explain(Map<String, Object> selector, Map<String, Object> sort) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("selector", selector);
        if (sort != null && !sort.isEmpty()) body.put("sort", sort);
        return post("/" + database + "/_explain", body);
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
