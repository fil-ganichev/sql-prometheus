package org.lokrusta.sqlprometheus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Блок {@code data} ответа query_range: {@code resultType=matrix}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatrixData(String resultType, List<MatrixSeries> result) {
}
