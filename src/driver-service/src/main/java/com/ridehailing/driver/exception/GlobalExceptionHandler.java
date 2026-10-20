package com.ridehailing.driver.exception;

import com.ridehailing.driver.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.ridehailing.driver.exception.InvalidInputException;
import com.ridehailing.driver.exception.NotFoundException;
import com.ridehailing.driver.exception.PlateTakenException;
import com.ridehailing.driver.exception.StateConflictException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * T07 — lỗi JSON thống nhất cho driver-service (contract §1.1): không bao giờ lộ stack trace,
 * mọi response lỗi chứa correlationId (§5 rule 4). Handler nghiệp vụ thuộc T09+; các handler
 * ở đây chuẩn bị sẵn cho controller tương lai + chặn path chưa có handler thành 404 JSON.
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String, String> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> details.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request body is invalid.",
                details.isEmpty() ? null : details, req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Malformed request body.", null, req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException e, HttpServletRequest req) {
        log.error("Data integrity violation (correlationId={})", CorrelationIdFilter.resolve(req), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error.", null, req);
    }

    // T09 — lỗi nghiệp vụ driver profile/availability/location/candidates (contract §1.2)
    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiError> invalidInput(InvalidInputException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getMessage(), null, req);
    }

    @ExceptionHandler(StateConflictException.class)
    public ResponseEntity<ApiError> stateConflict(StateConflictException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "STATE_CONFLICT", e.getMessage(), e.details(), req);
    }

    @ExceptionHandler(PlateTakenException.class)
    public ResponseEntity<ApiError> plateTaken(PlateTakenException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "PLATE_TAKEN", e.getMessage(), null, req);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null, req);
    }

    // T10 — lỗi internal reservation command (contract §1.2/§4.1/§4.2)
    @ExceptionHandler(CommandConflictException.class)
    public ResponseEntity<ApiError> commandConflict(CommandConflictException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "COMMAND_CONFLICT", e.getMessage(), null, req);
    }

    @ExceptionHandler(StaleVersionException.class)
    public ResponseEntity<ApiError> staleVersion(StaleVersionException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "STALE_VERSION", e.getMessage(), e.details(), req);
    }

    @ExceptionHandler(DriverNotAvailableException.class)
    public ResponseEntity<ApiError> driverNotAvailable(DriverNotAvailableException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "DRIVER_NOT_AVAILABLE", e.getMessage(), e.details(), req);
    }

    @ExceptionHandler(TombstonedReservationException.class)
    public ResponseEntity<ApiError> tombstoned(TombstonedReservationException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "TOMBSTONED_RESERVATION", e.getMessage(), e.details(), req);
    }

    // Tham số query sai kiểu/thiếu (candidates: lat/lng/radiusM/limit) -> 400 INVALID_INPUT
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParam(MissingServletRequestParameterException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                "Missing required query parameter: " + e.getParameterName(), null, req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> badParamType(MethodArgumentTypeMismatchException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_INPUT",
                "Invalid value for query parameter: " + e.getName(), null, req);
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
