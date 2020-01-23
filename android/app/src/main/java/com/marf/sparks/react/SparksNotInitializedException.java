package com.marf.sparks.react;

public final class SparksNotInitializedException extends RuntimeException {

    public SparksNotInitializedException(String message, Throwable cause) {
        super(message, cause);
    }

    public SparksNotInitializedException(String message) {
        super(message);
    }
}