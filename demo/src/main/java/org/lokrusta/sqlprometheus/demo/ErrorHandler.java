package org.lokrusta.sqlprometheus.demo;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Logs an unhandled request error and increments the {@code service_error} counter.
 */
@RestControllerAdvice
public class ErrorHandler {
    public static final String SERVICE_ERROR = "service_error";
    public static final String EXCEPTION = "exception";

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    private final MeterRegistry meterRegistry;

    public ErrorHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(ShopExceptions.NotFound.class)
    public ProblemDetail notFound(ShopExceptions.NotFound exception) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", exception);
    }

    @ExceptionHandler(ShopExceptions.Conflict.class)
    public ProblemDetail conflict(ShopExceptions.Conflict exception) {
        return problem(HttpStatus.CONFLICT, "Conflict", exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", exception);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception exception) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, exception.getClass().getSimpleName(), exception);
    }

    private ProblemDetail problem(HttpStatus status, String title, Exception exception) {
        log.error("Request failed", exception);
        meterRegistry.counter(SERVICE_ERROR, EXCEPTION, exception.getClass().getSimpleName()).increment();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, exception.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
