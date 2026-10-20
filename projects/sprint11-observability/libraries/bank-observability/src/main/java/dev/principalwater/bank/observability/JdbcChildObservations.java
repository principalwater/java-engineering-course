package dev.principalwater.bank.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;

// JDBC-наблюдения нужны как дочерние span запроса, сообщения или публикации outbox. Без
// родителя они были бы корневыми трассами фоновых опросов (раз в секунду на каждый воркер)
// и миграций Flyway и вытесняли бы реальные трассы из выборки.
final class JdbcChildObservations implements ObservationPredicate {
    // Префикс всех наблюдений datasource-micrometer: jdbc.connection, jdbc.query, jdbc.result-set.
    private static final String JDBC_PREFIX = "jdbc.";

    @Override
    public boolean test(String name, Observation.Context context) {
        if (!name.startsWith(JDBC_PREFIX)) {
            return true;
        }
        // datasource-micrometer связывает запрос с наблюдением соединения; отключённое соединение
        // остаётся родителем-заглушкой (NOOP), то есть настоящего родителя нет.
        return context.getParentObservation() instanceof Observation parent && !parent.isNoop();
    }
}
