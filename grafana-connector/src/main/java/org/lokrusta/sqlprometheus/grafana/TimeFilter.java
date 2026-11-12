package org.lokrusta.sqlprometheus.grafana;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Rewrites {@code $__timeFilter(column)} into bounds plus a rate window.
 * The window is the selected dashboard range, so {@code PERCENTILE_CONT} keeps a real quantile at the right edge.
 */
final class TimeFilter {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);
    private static final String MACRO = "$__timeFilter";

    private TimeFilter() {
    }

    static String apply(String sql, Long fromMillis, Long toMillis) {
        if (sql == null || !sql.contains(MACRO)) {
            return sql;
        }
        if (fromMillis == null || toMillis == null) {
            throw new QueryRejected("$__timeFilter requires from and to");
        }
        StringBuilder out = new StringBuilder(sql.length() + 160);
        boolean single = false;
        boolean doub = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !doub) {
                if (single && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append("''");
                    i++;
                    continue;
                }
                single = !single;
                out.append(c);
                continue;
            }
            if (c == '"' && !single) {
                doub = !doub;
                out.append(c);
                continue;
            }
            if (!single && !doub && sql.startsWith(MACRO, i)) {
                int cursor = i + MACRO.length();
                while (cursor < sql.length() && Character.isWhitespace(sql.charAt(cursor))) {
                    cursor++;
                }
                if (cursor < sql.length() && sql.charAt(cursor) == '(') {
                    int close = sql.indexOf(')', cursor + 1);
                    if (close < 0) {
                        throw new QueryRejected("Malformed $__timeFilter");
                    }
                    String column = sql.substring(cursor + 1, close).trim();
                    if (column.isEmpty()) {
                        throw new QueryRejected("Malformed $__timeFilter");
                    }
                    out.append(predicate(column, fromMillis, toMillis));
                    i = close;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String predicate(String column, long fromMillis, long toMillis) {
        String from = STAMP.format(Instant.ofEpochMilli(fromMillis));
        String to = STAMP.format(Instant.ofEpochMilli(toMillis));
        long fromSeconds = Math.floorDiv(fromMillis, 1000L);
        long toSeconds = Math.floorDiv(toMillis, 1000L);
        return column + " > TIMESTAMP '" + to + "' - (INTERVAL '" + toSeconds + "' SECOND - INTERVAL '" + fromSeconds
                + "' SECOND) AND " + column + " >= TIMESTAMP '" + from + "' AND " + column + " <= TIMESTAMP '" + to + "'";
    }
}
