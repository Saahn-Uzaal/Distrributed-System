package com.ridehailing.identity.exception;

import com.ridehailing.identity.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** T06 — lỗi JSON thống nhất {code, message, correlationId, details}; không bao giờ lộ stack trace (contract §1.1). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EmailTakenException.class)
    public ResponseEntity<ApiError> emailTaken(EmailTakenException e, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "EMAIL_TAKEN", e.getMessage(), null, req);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> invalidCredentials(InvalidCredentialsException e, HttpServletRequest req) {
        // Cùng message cho email sai và password sai — không lộ chi tiết (contract §3.1).
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", InvalidCredentialsException.MESSAGE, null, req);
    }

    @ExceptionHandler(InvalidInputException.class)
    public ResponseEntity<ApiError> invalidInput(InvalidInputException e, HttpServletRequest req) {
        // F04 — validation phía service (password > 72 byte UTF-8, fullName ngoài 2–100 sau trim...)
        Map<String, String> details = new LinkedHashMap<>();
        if (e.getField() != null) {
            details.put(e.getField(), e.getMessage());
        }
        return build(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getMessage(),
                details.isEmpty() ? null : details, req);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiError> unauthorized(UnauthorizedException e, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage(), null, req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e, HttpServletRequest req) {
        Map<String, String> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> details.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "Request body is invalid.", details, req);
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
        // F03 — race existsByEmail/save: unique constraint email là cơ chế bảo đảm cuối cùng.
        // Chỉ vi phạm unique key của users.email -> 409 EMAIL_TAKEN; constraint khác giữ 500 để phân biệt.
        String msg = String.valueOf(e.getMessage());
        if (msg.contains("users_email_key")) {
            return build(HttpStatus.CONFLICT, "EMAIL_TAKEN", "Email is already registered.", null, req);
        }
        log.error("Data integrity violation other than users.email unique key (correlationId={})",
                CorrelationIdFilter.resolve(req), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error.", null, req);
    }

    // F04 — lỗi phía client trả đúng status theo contract §1.2, không gom thành 500
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.", null, req);
    }

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest req) {
        log.error("Unexpected error (correlationId={})", CorrelationIdFilter.resolve(req), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal server error.", null, req);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, Object details,
                                           HttpServletRequest req) {
        return ResponseEntity.status(status).body(new ApiError(code, message, CorrelationIdFilter.resolve(req), details));
    }
}
