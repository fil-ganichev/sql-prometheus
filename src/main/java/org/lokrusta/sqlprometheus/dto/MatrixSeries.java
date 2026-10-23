package org.lokrusta.sqlprometheus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Один ряд matrix-ответа.
 * {@code values} — массив пар {@code [unix-timestamp, "sample"]}, где время число, а значение строка.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatrixSeries(Map<String, String> metric, List<List<Object>> values) {
}
