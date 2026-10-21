package dev.principalwater.bank.contract;

public final class ProbePaths {
    public static final String LIVENESS = "/actuator/health/liveness";
    public static final String READINESS = "/actuator/health/readiness";
    // Единственный разрешённый без токена путь метрик; изоляцию обеспечивает сетевая политика.
    public static final String PROMETHEUS = "/actuator/prometheus";

    private ProbePaths() {}
}
