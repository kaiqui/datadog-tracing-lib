package com.kailima.datadog.exception;

public class DataDogTracingException extends RuntimeException {
    public DataDogTracingException(String message, Throwable cause) {
        super(message, cause);
    }
}