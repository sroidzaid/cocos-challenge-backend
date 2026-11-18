package com.cocos.challenge.shared.infrastructure.web;

import java.util.Comparator;
import java.util.List;

import com.cocos.challenge.shared.domain.BusinessRuleViolationException;
import com.cocos.challenge.shared.domain.ConflictException;
import com.cocos.challenge.shared.domain.DomainException;
import com.cocos.challenge.shared.domain.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Centralized error handling. Every error response is an RFC 9457 {@link ProblemDetail} enriched with:
 * <ul>
 *     <li>{@code code}: stable, machine-readable error code (part of the API contract);</li>
 *     <li>{@code correlationId}: id of the request, to correlate with logs;</li>
 *     <li>{@code errors}: field-level details for validation failures.</li>
 * </ul>
 * Extends {@link ResponseEntityExceptionHandler} so standard Spring MVC errors (405, 415, missing parameters,
 * type mismatches...) get the same shape. Unexpected errors never leak internal details to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** Field-level validation error exposed in the {@code errors} property. */
    public record FieldViolation(String field, String message) {
    }

    // ---- Business errors -------------------------------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException ex) {
        return domainProblem(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        return domainProblem(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    ProblemDetail handleBusinessRule(BusinessRuleViolationException ex) {
        return domainProblem(HttpStatus.UNPROCESSABLE_CONTENT, ex);
    }

    // ---- Validation errors -----------------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldViolation(
                        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(violations));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // A @Valid body validated together with constrained path variables arrives here as ParameterErrors:
        // report its fields (e.g. "quantity"), not the parameter name ("request").
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                error instanceof FieldError fieldError
                                        ? fieldError.getField()
                                        : result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(violations));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(validationProblem(
                List.of(new FieldViolation(ex.getParameterName(), "is required"))));
    }

    /** A required header (e.g. Idempotency-Key) is missing: a validation error on that header. */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (ex instanceof MissingRequestHeaderException missing) {
            return ResponseEntity.badRequest().body(validationProblem(
                    List.of(new FieldViolation(missing.getHeaderName(), "is required"))));
        }
        return super.handleServletRequestBindingException(ex, headers, status, request);
    }

    /** A header that cannot be converted (e.g. Idempotency-Key that is not a UUID) is a validation error on it. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            RequestHeader header = mismatch.getParameter().getParameterAnnotation(RequestHeader.class);
            if (header != null) {
                String name = header.name().isEmpty() ? header.value() : header.name();
                return ResponseEntity.badRequest().body(validationProblem(
                        List.of(new FieldViolation(name, "has an invalid format"))));
            }
        }
        return super.handleTypeMismatch(ex, headers, status, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field))
                .toList();
        return validationProblem(violations);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest()
                .body(problem(HttpStatus.BAD_REQUEST, MALFORMED_REQUEST, "Request body is missing or malformed."));
    }

    // ---- Infrastructure errors -------------------------------------------------------------------------------

    /** Transient database problems (lock timeouts, deadlocks, connection failures): the client may retry. */
    @ExceptionHandler({TransientDataAccessException.class, DataAccessResourceFailureException.class})
    ProblemDetail handleTransientDataAccess(RuntimeException ex) {
        log.warn("Transient data access failure", ex);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE,
                "The service is temporarily unavailable. Please retry later.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR, "An unexpected error occurred.");
    }

    /**
     * Enriches the ProblemDetail produced for every standard Spring MVC exception handled by the base class.
     * Hooked here (not in {@code handleExceptionInternal}) because the body is only resolved at this point.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            enrich(problem, codeFor(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    // ---- Helpers ---------------------------------------------------------------------------------------------

    private ProblemDetail domainProblem(HttpStatus status, DomainException ex) {
        log.info("Business error {}: {}", ex.getCode(), ex.getMessage());
        return problem(status, ex.getCode(), ex.getMessage());
    }

    private ProblemDetail validationProblem(List<FieldViolation> violations) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed.");
        problem.setProperty("errors", violations);
        return problem;
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        return enrich(ProblemDetail.forStatusAndDetail(status, detail), code);
    }

    private ProblemDetail enrich(ProblemDetail problem, String code) {
        if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
            problem.setProperty("code", code);
        }
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem;
    }

    private static String codeFor(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "HTTP_" + statusCode.value();
    }
}
