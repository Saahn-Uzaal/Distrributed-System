package com.ridehailing.identity.domain;

/** Vai trò user (spec §7): client không thể tự đăng ký ADMIN. */
public enum Role {
    PASSENGER,
    DRIVER
}
