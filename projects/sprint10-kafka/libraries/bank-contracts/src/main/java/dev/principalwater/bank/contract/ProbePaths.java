package dev.principalwater.bank.contract;

public final class ProbePaths {
    public static final String LIVENESS = "/actuator/health/liveness";
    public static final String READINESS = "/actuator/health/readiness";

    private ProbePaths() {}
}
