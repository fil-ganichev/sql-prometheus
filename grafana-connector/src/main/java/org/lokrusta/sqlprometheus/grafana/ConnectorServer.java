package org.lokrusta.sqlprometheus.grafana;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HTTP entry point used by Grafana Infinity. SQL is executed as received.
 */
public final class ConnectorServer implements AutoCloseable {
    public static final int DEFAULT_THREADS = 8;

    private final HttpServer server;
    private final ExecutorService workers;
    private final QueryService queries;

    private ConnectorServer(HttpServer server, ExecutorService workers, QueryService queries) {
        this.server = server;
        this.workers = workers;
        this.queries = queries;
    }

    public static ConnectorServer start(int port, String jdbcUrl) throws IOException {
        return start(port, jdbcUrl, DEFAULT_THREADS);
    }

    public static ConnectorServer start(int port, String jdbcUrl, int threads) throws IOException {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be a positive integer");
        }
        QueryService queries = new QueryService(jdbcUrl);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        ConnectorServer connector = new ConnectorServer(server, workers, queries);
        server.createContext("/health", connector::health);
        server.createContext("/query", connector::query);
        server.setExecutor(workers);
        server.start();
        return connector;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, QueryService.errorBody("Method not allowed"));
            return;
        }
        write(exchange, 200, "{\"status\":\"up\"}".getBytes(StandardCharsets.UTF_8));
    }

    private void query(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        if (!"POST".equals(exchange.getRequestMethod())) {
            write(exchange, 405, QueryService.errorBody("Method not allowed"));
            return;
        }
        QueryService.Response response = queries.query(body);
        write(exchange, response.status(), response.body());
    }

    private static void write(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
