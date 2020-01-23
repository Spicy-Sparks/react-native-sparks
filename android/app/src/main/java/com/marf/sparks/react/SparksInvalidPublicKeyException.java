package com.marf.sparks.react;

class SparksInvalidPublicKeyException extends RuntimeException {

    public SparksInvalidPublicKeyException(String message, Throwable cause) {
        super(message, cause);
    }

    public SparksInvalidPublicKeyException(String message) {
        super(message);
    }
}