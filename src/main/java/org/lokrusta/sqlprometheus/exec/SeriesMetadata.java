package org.lokrusta.sqlprometheus.exec;

import java.util.List;

public record SeriesMetadata(boolean present, List<String> labels) {
    public SeriesMetadata {
        labels = List.copyOf(labels);
    }

    public static SeriesMetadata absent() {
        return new SeriesMetadata(false, List.of());
    }
}
