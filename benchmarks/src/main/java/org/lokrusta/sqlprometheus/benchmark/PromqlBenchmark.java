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

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class PromqlBenchmark {
    @Param({
            "rate_create_order_success",
            "irate_get_categories_success",
            "increase_get_user_orders",
            "sum_rate_operation_code"
    })
    public String pair;

    private HttpClient client;
    private String promql;
    private RecordedWindow window;

    @Setup(Level.Trial)
    public void open() throws Exception {
        QueryRunner.waitUntilReady();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        promql = QueryPair.named(pair).promql();
        window = RecordedWindow.load();
    }

    @TearDown(Level.Trial)
    public void close() {
        client = null;
    }

    @Benchmark
    public int query() throws Exception {
        return QueryRunner.drainPromql(client, promql, window);
    }
}
