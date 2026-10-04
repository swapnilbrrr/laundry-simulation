package com.smartlaundry.exception;

/** Raised when a simulation control action is invalid for the current state. */
public class SimulationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SimulationException(String message) {
        super(message);
    }
}
