package com.ridehailing.identity.exception;

/** F04 — lỗi validation phía service (ngoài Bean Validation của DTO) -> 400 INVALID_INPUT, không 500. */
public class InvalidInputException extends RuntimeException {

    private final String field; // nullable — ghi vào details khi biết trường sai

    public InvalidInputException(String message) {
        this(message, null);
    }

    public InvalidInputException(String message, String field) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
