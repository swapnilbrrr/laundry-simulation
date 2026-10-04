package com.smartlaundry.simulation;

/**
 * Immutable configuration for one simulation run.
 * Default values match the CT074-3-2 Smart Laundry requirements.
 */
public record SimulationConfig(
        int customers, int arrivalMaxMs,
        int washMinMs, int washMaxMs,
        int dryMinMs, int dryMaxMs,
        int payMinMs, int payMaxMs, int payRetryMs,
        int washRecoveryMs, int kioskRecoveryMs, int ownerResponseMs,
        int congestionThreshold,
        double washFailureProb, double payFailureProb,
        FailureMode failureMode) {

    public static final int WASHERS = 6;
    public static final int DRYERS = 4;
    public static final int KIOSKS = 2;

    public SimulationConfig {
        if (customers <= 0) throw new IllegalArgumentException("customers must be positive");
        if (arrivalMaxMs < 0) throw new IllegalArgumentException("arrivalMaxMs must be non-negative");
        if (washMinMs < 0 || washMaxMs < washMinMs) throw new IllegalArgumentException("invalid wash timing");
        if (dryMinMs < 0 || dryMaxMs < dryMinMs) throw new IllegalArgumentException("invalid dry timing");
        if (payMinMs < 0 || payMaxMs < payMinMs) throw new IllegalArgumentException("invalid payment timing");
        if (payRetryMs < 0 || washRecoveryMs < 0 || kioskRecoveryMs < 0 || ownerResponseMs < 0) {
            throw new IllegalArgumentException("recovery/retry timings must be non-negative");
        }
        if (congestionThreshold <= 0) throw new IllegalArgumentException("congestionThreshold must be positive");
        if (washFailureProb < 0 || washFailureProb > 1 || payFailureProb < 0 || payFailureProb > 1) {
            throw new IllegalArgumentException("failure probabilities must be between 0 and 1");
        }
        if (failureMode == null) failureMode = FailureMode.RANDOM;
    }

    public static SimulationConfig defaults() {
        return new SimulationConfig(
                50, 3000,
                4000, 6000,
                3000, 5000,
                1000, 2000,
                2000,
                2000, 1000, 5000,
                30,
                0.05, 0.05,
                FailureMode.RANDOM);
    }

    private static int scale(int value, double factor) {
        return Math.max(0, (int) Math.round(value * factor));
    }

    /** Used only by automated tests to keep the concurrency checks fast. */
    public SimulationConfig scaled(double factor) {
        return new SimulationConfig(
                customers,
                scale(arrivalMaxMs, factor),
                scale(washMinMs, factor), scale(washMaxMs, factor),
                scale(dryMinMs, factor), scale(dryMaxMs, factor),
                scale(payMinMs, factor), scale(payMaxMs, factor),
                scale(payRetryMs, factor),
                scale(washRecoveryMs, factor), scale(kioskRecoveryMs, factor),
                scale(ownerResponseMs, factor),
                congestionThreshold,
                washFailureProb, payFailureProb, failureMode);
    }

    /** Test helper; production UI always uses the required 0–3 second arrival range. */
    public SimulationConfig withArrivalMaxMs(int value) {
        return new SimulationConfig(customers, value, washMinMs, washMaxMs, dryMinMs, dryMaxMs,
                payMinMs, payMaxMs, payRetryMs, washRecoveryMs, kioskRecoveryMs, ownerResponseMs,
                congestionThreshold, washFailureProb, payFailureProb, failureMode);
    }

    public SimulationConfig withFailureMode(FailureMode value) {
        return new SimulationConfig(customers, arrivalMaxMs, washMinMs, washMaxMs, dryMinMs, dryMaxMs,
                payMinMs, payMaxMs, payRetryMs, washRecoveryMs, kioskRecoveryMs, ownerResponseMs,
                congestionThreshold, washFailureProb, payFailureProb, value);
    }

    public SimulationConfig withFailureProbabilities(double wash, double pay) {
        return new SimulationConfig(customers, arrivalMaxMs, washMinMs, washMaxMs, dryMinMs, dryMaxMs,
                payMinMs, payMaxMs, payRetryMs, washRecoveryMs, kioskRecoveryMs, ownerResponseMs,
                congestionThreshold, wash, pay, failureMode);
    }

    public SimulationConfig withPayRetryMs(int value) {
        return new SimulationConfig(customers, arrivalMaxMs, washMinMs, washMaxMs, dryMinMs, dryMaxMs,
                payMinMs, payMaxMs, value, washRecoveryMs, kioskRecoveryMs, ownerResponseMs,
                congestionThreshold, washFailureProb, payFailureProb, failureMode);
    }
}
