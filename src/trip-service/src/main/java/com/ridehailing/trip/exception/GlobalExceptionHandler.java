package com.ridehailing.trip.exception;

import com.ridehailing.trip.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import com.ridehailing.trip.map.MapUpstreamException;
import com.ridehailing.trip.map.RouteNotFoundException;

/**
 * T07 — lỗi JSON thống nhất cho trip-service (contract §1.1): không bao giờ lộ stack trace,
 * mọi response lỗi chứa correlationId (§5 rule 4). Handler nghiệp vụ thuộc T12+; các handler
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

    // N03 (T07-11): body > 64 KiB -> 413 PAYLOAD_TOO_LARGE
    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ApiError> payloadTooLarge(PayloadTooLargeException e, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", e.getMessage(), null, req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException e, HttpServletRequest req) {
        log.error("Data integrity violation (correlationId={})", CorrelationIdFilter.resolve(req), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error.", null, req);
    }

    // F04 — lỗi phía client trả đúng status theo contract §1.2, không gom thành 500
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Method " + e.getMethod() + " is not supported for this endpoint.", null, req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaTypeNotSupported(HttpMediaTypeNotSupportedException e, HttpServletRequest req) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type " + e.getContentType() + " is not supported.", null, req);
    }

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

    // T12 — lỗi nghiệp vụ trip quote/create/list/get (contract §1.2/§3.3)
    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiError> invalidInput(InvalidInputException e, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getMessage(), null, req);
    }

    @ExceptionHandler(IdempotencyMismatchException.class)
    public ResponseEntity<ApiError> idempotencyMismatch(IdempotencyMismatchException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_MISMATCH", e.getMessage(), e.details(), req);
    }

    // T12-6 (N28): passenger đã có trip chưa terminal -> 409 ACTIVE_TRIP_EXISTS
    @ExceptionHandler(ActiveTripExistsException.class)
    public ResponseEntity<ApiError> activeTripExists(ActiveTripExistsException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "ACTIVE_TRIP_EXISTS", e.getMessage(), e.details(), req);
    }

    // T18–T20 — chuyển trạng thái sai / driver không phải người được offer (contract §1.2)
    @ExceptionHandler(StateConflictException.class)
    public ResponseEntity<ApiError> stateConflict(StateConflictException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "STATE_CONFLICT", e.getMessage(), e.details(), req);
    }

    @ExceptionHandler(NotOfferedDriverException.class)
    public ResponseEntity<ApiError> notOfferedDriver(NotOfferedDriverException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "NOT_OFFERED_DRIVER", e.getMessage(), e.details(), req);
    }

    // T22 — start/complete bởi driver không phải người được assign (contract §1.2/§3.3)
    @ExceptionHandler(NotAssignedDriverException.class)
    public ResponseEntity<ApiError> notAssignedDriver(NotAssignedDriverException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "NOT_ASSIGNED_DRIVER", e.getMessage(), e.details(), req);
    }

    @ExceptionHandler(TripNotFoundException.class)
    public ResponseEntity<ApiError> tripNotFound(TripNotFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), null, req);
    }

    @ExceptionHandler(RouteNotFoundException.class)
    public ResponseEntity<ApiError> routeNotFound(RouteNotFoundException e, HttpServletRequest req) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_NOT_FOUND", e.getMessage(), null, req);
    }

    @ExceptionHandler(MapUpstreamException.class)
    public ResponseEntity<ApiError> mapUnavailable(MapUpstreamException e, HttpServletRequest req) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "MAP_SERVICE_UNAVAILABLE",
                "Dịch vụ bản đồ tạm thời không khả dụng. Vui lòng thử lại.", null, req);
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
