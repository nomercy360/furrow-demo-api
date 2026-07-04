package demo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTest {

    static Api api;
    static HttpClient client;

    @BeforeAll
    static void start() throws IOException {
        api = new Api(0, "test-1.0", 0.0);
        api.start();
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stop() {
        api.stop();
    }

    static HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rootReturnsVersion() throws Exception {
        HttpResponse<String> res = get("/");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("\"version\":\"test-1.0\""));
    }

    @Test
    void healthzOk() throws Exception {
        HttpResponse<String> res = get("/healthz");
        assertEquals(200, res.statusCode());
        assertEquals("ok\n", res.body());
    }

    @Test
    void healthzNotReadyWhenConfiguredToExitAfterStart() throws Exception {
        Api exiting = new Api(0, "test-1.0", 0.0, true);
        exiting.start();
        try {
            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + exiting.port() + "/healthz")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(503, res.statusCode());
            assertEquals("exiting after start\n", res.body());
        } finally {
            exiting.stop();
        }
    }

    @Test
    void metricsExposeCounters() throws Exception {
        get("/");
        HttpResponse<String> res = get("/metrics");
        assertEquals(200, res.statusCode());
        assertTrue(res.body().contains("app_info{version=\"test-1.0\"} 1"));
        assertTrue(res.body().contains("http_requests_total{path=\"/\",code=\"200\""));
    }

    @Test
    void failRateInjectsErrors() throws Exception {
        Api failing = new Api(0, "test-1.0", 1.0);
        failing.start();
        try {
            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + failing.port() + "/")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(500, res.statusCode());
        } finally {
            failing.stop();
        }
    }
}
