package org.lokrusta.sqlprometheus.grafana;

/**
 * Starts the HTTP process. The JDBC URL is the only Prometheus setting:
 * driver options stay in that URL.
 */
public final class ConnectorMain {
    private ConnectorMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(setting(args, "--port=", "GRAFANA_CONNECTOR_PORT", "8081"));
        int threads = Integer.parseInt(setting(args, "--threads=", "GRAFANA_CONNECTOR_THREADS",
                Integer.toString(ConnectorServer.DEFAULT_THREADS)));
        String jdbcUrl = setting(args, "--jdbc=", "PROMETHEUS_JDBC_URL", null);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("Prometheus JDBC URL is required: --jdbc= or PROMETHEUS_JDBC_URL");
        }
        ConnectorServer server = ConnectorServer.start(port, jdbcUrl, threads);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("grafana-connector listening on " + server.port());
    }

    private static String setting(String[] args, String prefix, String env, String fallback) {
        String value = fallback;
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            value = fromEnv;
        }
        for (String arg : args) {
            if (arg.startsWith(prefix) && arg.length() > prefix.length()) {
                value = arg.substring(prefix.length());
            }
        }
        return value;
    }
}
