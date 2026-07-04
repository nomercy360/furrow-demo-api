package demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.LongAdder;

/**
 * Zero-dependency demo API for canary testing.
 *
 * Env:
 *   PORT        listen port (default 8080)
 *   APP_VERSION reported version, shows up in responses and metrics (default "dev")
 *   FAIL_RATE   fraction [0.0..1.0] of "/" requests answered with 500 — lets a
 *               canary release misbehave on purpose so the metric gate trips
 */
public final class Api {

    private final String version;
    private final double failRate;
    private final ConcurrentHashMap<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final HttpServer server;

    public Api(int port, String version, double failRate) throws IOException {
        this.version = version;
        this.failRate = failRate;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::root);
        server.createContext("/healthz", this::healthz);
        server.createContext("/metrics", this::metrics);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void root(HttpExchange ex) throws IOException {
        if (ThreadLocalRandom.current().nextDouble() < failRate) {
            count("/", 500);
            respond(ex, 500, "application/json",
                    "{\"error\":\"injected failure\",\"version\":\"" + version + "\"}\n");
            return;
        }
        count("/", 200);
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            host = "unknown";
        }
        respond(ex, 200, "application/json",
                "{\"app\":\"furrow-demo-api\",\"version\":\"" + version + "\",\"host\":\"" + host + "\"}\n");
    }

    private void healthz(HttpExchange ex) throws IOException {
        count("/healthz", 200);
        respond(ex, 200, "text/plain", "ok\n");
    }

    private void metrics(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# HELP app_info Build/version info.\n");
        sb.append("# TYPE app_info gauge\n");
        sb.append("app_info{version=\"").append(version).append("\"} 1\n");
        sb.append("# HELP http_requests_total Requests served, by path and code.\n");
        sb.append("# TYPE http_requests_total counter\n");
        requests.forEach((key, count) -> {
            int sep = key.lastIndexOf('|');
            sb.append("http_requests_total{path=\"").append(key, 0, sep)
              .append("\",code=\"").append(key.substring(sep + 1))
              .append("\",version=\"").append(version)
              .append("\"} ").append(count.sum()).append('\n');
        });
        respond(ex, 200, "text/plain; version=0.0.4", sb.toString());
    }

    private void count(String path, int code) {
        requests.computeIfAbsent(path + "|" + code, k -> new LongAdder()).increment();
    }

    private static void respond(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(env("PORT", "8080"));
        String version = env("APP_VERSION", "dev");
        double failRate = Double.parseDouble(env("FAIL_RATE", "0"));
        Api api = new Api(port, version, failRate);
        api.start();
        System.out.printf("furrow-demo-api version=%s port=%d failRate=%.2f%n", version, port, failRate);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }
}
