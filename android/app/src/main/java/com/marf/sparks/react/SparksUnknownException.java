package com.marf.sparks.react;

class SparksUnknownException extends RuntimeException {

    public SparksUnknownException(String message, Throwable cause) {
        super(message, cause);
    }

    public SparksUnknownException(String message) {
        super(message);
    }
}