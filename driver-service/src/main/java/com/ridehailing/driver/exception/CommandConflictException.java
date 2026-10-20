package com.ridehailing.driver.exception;

/** T10 — 409 COMMAND_CONFLICT (contract §1.2): cùng commandId, request hash khác. */
public class CommandConflictException extends RuntimeException {
    public CommandConflictException(String message) {
        super(message);
    }
}
