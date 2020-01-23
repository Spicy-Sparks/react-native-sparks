package com.marf.sparks.react;

public enum SparksUpdateState {
    RUNNING(0),
    PENDING(1),
    LATEST(2);

    private final int value;
    SparksUpdateState(int value) {
        this.value = value;
    }
    public int getValue() {
        return this.value;
    }
}