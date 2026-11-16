package org.lokrusta.sqlprometheus.benchmark;

import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.net.http.HttpClient;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;

public final class BenchmarkMain {
    private BenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        QueryRunner.waitUntilReady();
        RecordedWindow window = RecordedWindow.load();
        System.out.println("Recorded window " + window.startLiteral() + " .. " + window.endLiteral()
                + " step " + QueryRunner.STEP);
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        try (Connection connection = DriverManager.getConnection(QueryRunner.JDBC_URL)) {
            QueryRunner.verify(connection, client, window);
        }
        try (Connection connection = DriverManager.getConnection(QueryRunner.CACHED_JDBC_URL)) {
            QueryRunner.verify(connection, client, window);
        }
        Options options = new OptionsBuilder()
                .include("org\\.lokrusta\\.sqlprometheus\\.benchmark\\.(SqlCachedBenchmark|SqlBenchmark|PromqlBenchmark)")
                .shouldFailOnError(true)
                .jvmArgs(
                        "--add-opens=java.base/java.lang=ALL-UNNAMED",
                        "--add-opens=java.base/java.util=ALL-UNNAMED",
                        "--add-opens=java.base/java.nio=ALL-UNNAMED",
                        "-Dbenchmark.prometheusData=" + RecordedWindow.dataDir().toAbsolutePath())
                .build();
        new Runner(options).run();
    }
}
