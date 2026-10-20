package com.ridehailing.trip.security;

/** Roles user theo contract api-contracts.md §2 — chỉ PASSENGER/DRIVER (không có ADMIN). */
public enum Role {
    PASSENGER,
    DRIVER
}
