package dev.queryguard.admin.web;

import java.net.URI;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * RFC 7807 bodies for our own errors. Spring already produces them for bad JSON, missing
 * parameters and Bean Validation failures (spring.mvc.problemdetails.enabled).
 */
@RestControllerAdvice
public class ProblemAdvice {

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        p.setType(URI.create("https://github.com/thotaarchitha/queryguard/blob/main/docs/api-errors.md#" + e.status().value()));
        if (!e.errors().isEmpty()) {
            p.setProperty("errors", e.errors());
        }
        return p;
    }

    /** Validation of list elements (List<@Valid MaskingPolicy>) surfaces as this, not as a binding error. */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ProblemDetail constraint(jakarta.validation.ConstraintViolationException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request failed validation");
        p.setProperty("errors", e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage()).sorted().toList());
        return p;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ProblemDetail duplicate(DuplicateKeyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "a rule with that id already exists");
    }
}
