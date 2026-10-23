package org.lokrusta.sqlprometheus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Ответ {@code GET /api/v1/label/__name__/values}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LabelValuesResponse(String status, List<String> data, String errorType, String error) {
}
