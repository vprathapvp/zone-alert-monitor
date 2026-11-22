package com.geoalert.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A small HTTP client that talks to the application exactly as an external caller would. */
public final class Api {

    public record Response(int status, String contentType, JsonNode body) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    private final String base;

    public Api(String base) {
        this.base = base;
    }

    public String token(String username) {
        return TOKENS.computeIfAbsent(base + "|" + username, k -> {
            Response r = send("POST", "/api/v1/auth/login", null, "application/json",
                    "{\"username\":\"" + username + "\",\"password\":\"" + IntegrationTest.PASSWORD_PREFIX + username + "\"}");
            if (r.status() != 200) {
                throw new IllegalStateException("login failed for " + username + ": " + r.status());
            }
            return r.body().get("accessToken").asText();
        });
    }

    public Response batch(List<String> lines) {
        return batch(String.join("\n", lines) + "\n");
    }

    public Response batch(String body) {
        return send("POST", "/api/v1/reports/batch", token("feed"), "application/x-ndjson", body);
    }

    public Response batchBytes(byte[] body) {
        return send("POST", "/api/v1/reports/batch", token("feed"), "application/x-ndjson",
                HttpRequest.BodyPublishers.ofByteArray(body));
    }

    public Response get(String user, String path) {
        return send("GET", path, user == null ? null : token(user), null, (String) null);
    }

    public Response post(String user, String path, String json) {
        return send("POST", path, token(user), "application/json", json == null ? "" : json);
    }

    public Response send(String method, String path, String token, String contentType, String body) {
        return send(method, path, token, contentType,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    }

    public Response send(String method, String path, String token, String contentType, HttpRequest.BodyPublisher body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        b.method(method, body);
        try {
            HttpResponse<String> res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = res.body() == null || res.body().isEmpty() ? null : MAPPER.readTree(res.body());
            return new Response(res.statusCode(), res.headers().firstValue("Content-Type").orElse(""), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
