package org.lokrusta.sqlprometheus.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class SqlBenchmark {
    @Param({
            "rate_create_order_success",
            "irate_get_categories_success",
            "increase_get_user_orders",
            "sum_rate_operation_code"
    })
    public String pair;

    private Connection connection;
    private String sql;

    @Setup(Level.Trial)
    public void open() throws Exception {
        QueryRunner.waitUntilReady();
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        connection = DriverManager.getConnection(QueryRunner.JDBC_URL);
        sql = QueryPair.named(pair).sql(RecordedWindow.load());
    }

    @TearDown(Level.Trial)
    public void close() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Benchmark
    public int query() throws Exception {
        return QueryRunner.drainSql(connection, sql);
    }
}
