package com.geoalert.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves as application/problem+json with a catalogue code. Framework messages are
 * replaced by fixed client-facing text so no class names, SQL or stack traces leak.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> api(ApiException e, HttpServletRequest req) {
        return Problems.entity(e.code(), e.code().status().value(), e.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return Problems.entity(ErrorCode.VALIDATION_FAILED, 400,
                "Parameter '" + e.getName() + "' has an invalid value", req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled error on {} {}", req.getMethod(), req.getRequestURI(), e);
        return Problems.entity(ErrorCode.INTERNAL_ERROR, 500, "An internal error occurred", req.getRequestURI());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        int status = statusCode.value();
        ErrorCode code = ErrorCode.forStatus(status);
        String detail = switch (ex) {
            case HttpMessageNotReadableException ignored -> "Request body is missing or not valid JSON";
            case HttpMediaTypeNotSupportedException ignored -> "Content-Type is not supported by this endpoint";
            case HttpRequestMethodNotSupportedException ignored -> "Method not allowed";
            case MissingServletRequestParameterException m -> "Parameter '" + m.getParameterName() + "' is required";
            case NoResourceFoundException ignored -> "Not found";
            default -> status >= 500 ? "An internal error occurred" : "Request is not valid";
        };
        if (status >= 500) {
            log.error("framework error", ex);
        }
        String instance = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
        HttpHeaders out = new HttpHeaders();
        if (headers != null && headers.getAllow() != null && !headers.getAllow().isEmpty()) {
            out.setAllow(headers.getAllow());
        }
        out.setContentType(Problems.PROBLEM_JSON);
        return new ResponseEntity<>(Problems.body(code, status, detail, instance), out, status);
    }
}
