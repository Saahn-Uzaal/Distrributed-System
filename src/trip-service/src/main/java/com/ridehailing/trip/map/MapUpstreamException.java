package com.ridehailing.trip.map;

public class MapUpstreamException extends RuntimeException {
    public MapUpstreamException(String message, Throwable cause) { super(message, cause); }
    public MapUpstreamException(String message) { super(message); }
}
