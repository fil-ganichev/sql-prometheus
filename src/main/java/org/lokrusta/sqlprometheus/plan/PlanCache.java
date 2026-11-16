package org.lokrusta.sqlprometheus.plan;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.apache.calcite.avatica.Meta;
import org.lokrusta.sqlprometheus.exec.PrometheusOptions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

final class PlanCache {
    // Janino does not report the size of the generated class. Charge a fixed estimate per plan,
    // the same way the schema cache charges a fixed cost per label.
    static final int CLASS_BYTES = 8192;

    private final int maxBytes;
    private final Cache<String, Meta.Signature> cache;
    private final Map<String, Integer> usage = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();
    private int usedBytes;

    PlanCache(PrometheusOptions options) {
        this.maxBytes = options.planCacheMaxBytes();
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(options.planCacheTtlSeconds()))
                .removalListener(this::onRemoval)
                .build();
    }

    static int weight(String sql) {
        return CLASS_BYTES + sql.length() * 2;
    }

    PreparedStatement open(Connection connection, String sql, int type, int concurrency, int holdability)
            throws SQLException {
        Meta.Signature cached = find(sql);
        if (cached != null) {
            return CalcitePlans.replay(connection, cached, type, concurrency, holdability);
        }
        PreparedStatement prepared = connection.prepareStatement(sql, type, concurrency, holdability);
        Meta.Signature signature = CalcitePlans.signature(prepared);
        if (signature != null) {
            store(sql, signature);
        }
        return prepared;
    }

    private Meta.Signature find(String sql) {
        lock.lock();
        try {
            Meta.Signature signature = cache.getIfPresent(sql);
            if (signature == null) {
                usage.remove(sql);
                return null;
            }
            usage.put(sql, weight(sql));
            return signature;
        } finally {
            lock.unlock();
        }
    }

    private void store(String sql, Meta.Signature signature) {
        int bytes = weight(sql);
        lock.lock();
        try {
            Meta.Signature raced = cache.getIfPresent(sql);
            if (raced != null) {
                usage.put(sql, weight(sql));
                return;
            }
            cache.put(sql, signature);
            usage.put(sql, bytes);
            usedBytes += bytes;
            evictOverflow(sql);
        } finally {
            lock.unlock();
        }
    }

    private void evictOverflow(String keep) {
        Iterator<Map.Entry<String, Integer>> iterator = usage.entrySet().iterator();
        while (usedBytes > maxBytes && usage.size() > 1 && iterator.hasNext()) {
            Map.Entry<String, Integer> eldest = iterator.next();
            if (eldest.getKey().equals(keep)) {
                continue;
            }
            iterator.remove();
            cache.invalidate(eldest.getKey());
            usedBytes -= eldest.getValue();
        }
    }

    private void onRemoval(String sql, Meta.Signature signature, RemovalCause cause) {
        if (cause != RemovalCause.EXPIRED || sql == null) {
            return;
        }
        lock.lock();
        try {
            Integer bytes = usage.remove(sql);
            if (bytes != null) {
                usedBytes -= bytes;
            }
        } finally {
            lock.unlock();
        }
    }
}
