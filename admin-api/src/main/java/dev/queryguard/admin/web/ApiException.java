package dev.queryguard.admin.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/** Errors we raise on purpose. ProblemAdvice turns them into RFC 7807 responses. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final List<String> errors;

    public ApiException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public ApiException(HttpStatus status, String message, List<String> errors) {
        super(message);
        this.status = status;
        this.errors = errors;
    }

    public HttpStatus status() {
        return status;
    }

    public List<String> errors() {
        return errors;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    public static ApiException invalid(List<String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "rule failed validation", errors);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, message);
    }
}
