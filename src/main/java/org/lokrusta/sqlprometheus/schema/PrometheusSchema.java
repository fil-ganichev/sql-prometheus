package org.lokrusta.sqlprometheus.schema;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.lokrusta.sqlprometheus.exec.PrometheusClient;
import org.lokrusta.sqlprometheus.exec.PrometheusOptions;
import org.lokrusta.sqlprometheus.exec.SeriesMetadata;
import org.lokrusta.sqlprometheus.sql.PrometheusSqlFunctions;
import org.lokrusta.sqlprometheus.sql.PrometheusSqlOperatorTable;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class PrometheusSchema extends AbstractSchema {
    private static final ThreadLocal<Set<String>> REQUESTED = ThreadLocal.withInitial(ConcurrentHashMap::newKeySet);

    private final String baseUrl;
    private final PrometheusOptions options;
    private final boolean sqlFunctions;
    private final Map<String, Table> fixedTables;
    private final Cache<String, SeriesMetadata> cache;
    private final Map<String, Integer> usage = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();
    private int usedBytes;
    private final Map<String, Table> remoteTables = new AbstractMap<>() {
        @Override
        public Table get(Object key) {
            if (!(key instanceof String name) || name.isEmpty()) {
                return null;
            }
            return table(name, cached(name));
        }

        @Override
        public Set<String> keySet() {
            resolveRequested();
            return presentNames();
        }

        @Override
        public Set<Entry<String, Table>> entrySet() {
            resolveRequested();
            Set<Entry<String, Table>> entries = new LinkedHashSet<>();
            for (String name : presentNames()) {
                entries.add(Map.entry(name, table(name, cached(name))));
            }
            return entries;
        }
    };

    public PrometheusSchema(String baseUrl) {
        this(baseUrl, PrometheusOptions.defaults());
    }

    public PrometheusSchema(String baseUrl, PrometheusOptions options) {
        this(baseUrl, options, true, null);
    }

    private PrometheusSchema(String baseUrl,
                             PrometheusOptions options,
                             boolean sqlFunctions,
                             Map<String, Table> fixedTables) {
        this.baseUrl = baseUrl;
        this.options = options;
        this.sqlFunctions = sqlFunctions;
        this.fixedTables = fixedTables;
        this.cache = fixedTables == null ? newCache(options) : null;
    }

    public static PrometheusSchema withoutFunctions(String baseUrl) {
        PrometheusOptions options = PrometheusOptions.defaults();
        return new PrometheusSchema(baseUrl, options, false, builtinTables(baseUrl, options));
    }

    /**
     * Records metric names mentioned by the statement that is about to be validated.
     * Calcite resolves a table only when its name is already in {@code getTableNames()}.
     */
    public static void noteTables(Collection<String> names) {
        Set<String> requested = REQUESTED.get();
        requested.clear();
        for (String name : names) {
            if (name != null && !name.isBlank()) {
                requested.add(name);
            }
        }
    }

    public static int weight(String name, SeriesMetadata metadata) {
        int bytes = 64 + name.length() * 2;
        for (String label : metadata.labels()) {
            bytes += 32 + label.length() * 2;
        }
        return bytes;
    }

    @Override
    protected Multimap<String, Function> getFunctionMultimap() {
        if (!sqlFunctions) {
            return ImmutableMultimap.of();
        }
        ImmutableMultimap.Builder<String, Function> builder = ImmutableMultimap.builder();
        for (String name : PrometheusSqlOperatorTable.functionNames()) {
            builder.put(name, ScalarFunctionImpl.create(PrometheusSqlFunctions.class, name));
        }
        return builder.build();
    }

    @Override
    protected Map<String, Table> getTableMap() {
        if (fixedTables != null) {
            return fixedTables;
        }
        return remoteTables;
    }

    private void resolveRequested() {
        for (String name : List.copyOf(REQUESTED.get())) {
            cached(name);
        }
    }

    private Set<String> presentNames() {
        lock.lock();
        try {
            Set<String> names = new LinkedHashSet<>();
            for (String name : List.copyOf(usage.keySet())) {
                SeriesMetadata metadata = cache.getIfPresent(name);
                if (metadata != null && metadata.present()) {
                    names.add(name);
                }
            }
            return names;
        } finally {
            lock.unlock();
        }
    }

    private SeriesMetadata cached(String name) {
        lock.lock();
        try {
            SeriesMetadata present = cache.getIfPresent(name);
            if (present != null) {
                usage.put(name, weight(name, present));
                return present;
            }
            forget(name);
        } finally {
            lock.unlock();
        }
        SeriesMetadata loaded = load(name);
        lock.lock();
        try {
            SeriesMetadata raced = cache.getIfPresent(name);
            if (raced != null) {
                usage.put(name, weight(name, raced));
                return raced;
            }
            int bytes = weight(name, loaded);
            cache.put(name, loaded);
            usage.put(name, bytes);
            usedBytes += bytes;
            evictOverflow(name);
            return loaded;
        } finally {
            lock.unlock();
        }
    }

    private void evictOverflow(String keep) {
        Iterator<Map.Entry<String, Integer>> iterator = usage.entrySet().iterator();
        while (usedBytes > options.schemaCacheMaxBytes() && usage.size() > 1 && iterator.hasNext()) {
            Map.Entry<String, Integer> eldest = iterator.next();
            if (eldest.getKey().equals(keep)) {
                continue;
            }
            iterator.remove();
            cache.invalidate(eldest.getKey());
            usedBytes -= eldest.getValue();
        }
    }

    private void forget(String name) {
        Integer bytes = usage.remove(name);
        if (bytes != null) {
            usedBytes -= bytes;
        }
    }

    private void onRemoval(String name, SeriesMetadata metadata, RemovalCause cause) {
        if (cause != RemovalCause.EXPIRED || name == null) {
            return;
        }
        lock.lock();
        try {
            forget(name);
        } finally {
            lock.unlock();
        }
    }

    private SeriesMetadata load(String name) {
        return PrometheusClient.series(
                baseUrl,
                name,
                options.connectTimeoutSeconds(),
                options.queryTimeoutSeconds());
    }

    private Table table(String name, SeriesMetadata metadata) {
        if (metadata == null || !metadata.present()) {
            return null;
        }
        return PrometheusTable.metric(baseUrl, name, metadata.labels(), options);
    }

    private Cache<String, SeriesMetadata> newCache(PrometheusOptions options) {
        // Caffeine drops entries by write time. Its own size policy is Window TinyLFU and rejects
        // a new metric when the metadata is larger than the 1% admission window, so the byte cap
        // evicts the least recently used name instead.
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(options.schemaCacheTtlSeconds()))
                .removalListener(this::onRemoval)
                .build();
    }

    private static Map<String, Table> builtinTables(String baseUrl, PrometheusOptions options) {
        return Map.of(
                "node_cpu_seconds_total", PrometheusTable.cpu(baseUrl, options),
                "container_cpu_usage_seconds_total", PrometheusTable.containerCpu(baseUrl, options),
                "kube_pod_info", PrometheusTable.podInfo(baseUrl, options),
                "http_request_duration_seconds_bucket", PrometheusTable.requestDuration(baseUrl, options));
    }
}
