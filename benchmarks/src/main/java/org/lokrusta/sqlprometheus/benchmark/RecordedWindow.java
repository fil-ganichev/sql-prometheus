package org.lokrusta.sqlprometheus.benchmark;

import org.apache.calcite.util.TimestampString;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record RecordedWindow(long startMillis, long endMillis) {
    private static final Pattern TIME = Pattern.compile("\"(minTime|maxTime)\"\\s*:\\s*(-?\\d+)");
    // query_range rejects a grid longer than this. The step is 15s, so the shared window is the
    // tail of the recorded blocks that still fits.
    private static final long STEP_MILLIS = 15_000L;
    private static final long MAX_POINTS = 11_000L;

    public static RecordedWindow load() throws IOException {
        Path data = dataDir();
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        boolean found = false;
        try (DirectoryStream<Path> blocks = Files.newDirectoryStream(data, "01*")) {
            for (Path block : blocks) {
                if (!Files.isDirectory(block)) {
                    continue;
                }
                Path meta = block.resolve("meta.json");
                if (!Files.isRegularFile(meta)) {
                    continue;
                }
                String text = Files.readString(meta);
                Long minTime = field(text, "minTime");
                Long maxTime = field(text, "maxTime");
                if (minTime == null || maxTime == null) {
                    continue;
                }
                min = Math.min(min, minTime);
                max = Math.max(max, maxTime);
                found = true;
            }
        }
        if (!found || min > max) {
            throw new IllegalStateException(
                    "Сначала снимите базу метрик, запустив демо (make demo-up) и дождавшись k6.");
        }
        long maxSpan = (MAX_POINTS - 1) * STEP_MILLIS;
        if (max - min > maxSpan) {
            min = max - maxSpan;
        }
        return new RecordedWindow(min, max);
    }

    public String startLiteral() {
        return literal(startMillis);
    }

    public String endLiteral() {
        return literal(endMillis);
    }

    static Path dataDir() {
        String override = System.getProperty("benchmark.prometheusData");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of("demo", "docker", "prometheus", "data");
    }

    private static String literal(long millis) {
        String text = TimestampString.fromMillisSinceEpoch(millis).toString();
        long back = new TimestampString(text).getMillisSinceEpoch();
        if (back != millis) {
            throw new IllegalStateException("Timestamp literal " + text + " does not round-trip to " + millis);
        }
        return text;
    }

    private static Long field(String text, String name) {
        Matcher matcher = TIME.matcher(text);
        while (matcher.find()) {
            if (name.equals(matcher.group(1))) {
                return Long.parseLong(matcher.group(2));
            }
        }
        return null;
    }
}
