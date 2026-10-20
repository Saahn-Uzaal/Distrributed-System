package com.ridehailing.matching.exception;

import com.ridehailing.matching.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * T07 — lỗi JSON thống nhất cho matching-service (contract §1.1): không bao giờ lộ stack trace,
 * mọi response lỗi chứa correlationId (§5 rule 4). matching-service không có REST API trong
 * contract §3/§4 — handler này chuẩn bị sẵn cho controller tương lai + chặn path chưa có
 * handler thành 404 JSON. Consumer nghiệp vụ thuộc T14/T15.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> noHandler(NoHandlerFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.", null, req);
    }

    // Spring Framework 7 (Boot 4): path chưa có handler -> NoResourceFoundException (fallback resource handler)
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.", null, req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException e, HttpServletRequest req) {
        log.error("Data integrity violation (correlationId={})", CorrelationIdFilter.resolve(req), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error.", null, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unexpected error (correlationId={})", CorrelationIdFilter.resolve(req), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error.", null, req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, Object details, HttpServletRequest req) {
        return ResponseEntity.status(status).body(new ApiError(code, message, CorrelationIdFilter.resolve(req), details));
    }
}
