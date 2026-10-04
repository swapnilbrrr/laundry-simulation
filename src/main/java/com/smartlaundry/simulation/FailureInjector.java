package com.smartlaundry.simulation;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Decides whether an attempt fails. Default: independent Bernoulli trial with p = 0.05
 * (nextDouble() is uniform in [0,1), so P(nextDouble() < 0.05) = 5%).
 * ThreadLocalRandom avoids contention on a shared Random instance.
 * FORCE_* modes additionally fail the first attempt of every 10th customer, for deterministic demos/tests.
 */
public final class FailureInjector {
    private final SimulationConfig config;

    public FailureInjector(SimulationConfig config) {
        this.config = config;
    }

    public boolean shouldFailWash(int customerId, int attempt) {
        if (config.failureMode() == FailureMode.FORCE_WASH && attempt == 1 && customerId % 10 == 0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < config.washFailureProb();
    }

    public boolean shouldFailPayment(int customerId, int attempt) {
        if (config.failureMode() == FailureMode.FORCE_PAYMENT && attempt == 1 && customerId % 10 == 0) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < config.payFailureProb();
    }
}
