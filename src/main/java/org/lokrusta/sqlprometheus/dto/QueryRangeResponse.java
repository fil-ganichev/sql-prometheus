package org.lokrusta.sqlprometheus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Ответ {@code GET /api/v1/query_range}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryRangeResponse(String status, MatrixData data, String errorType, String error) {
}
