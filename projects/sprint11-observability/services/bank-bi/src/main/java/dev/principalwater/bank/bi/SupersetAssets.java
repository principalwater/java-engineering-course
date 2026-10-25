package dev.principalwater.bank.bi;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class SupersetAssets {
    static final String DATABASE_FILE = "databases/bank.yaml";
    static final String DATABASE_NAME = "Bank aggregate analytics";
    static final String VERSION = "1.0.0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATABASE_ID = identity("database");
    private static final String VERSION_FIELD = "version";
    private static final String ROOT = "ROOT_ID";
    private static final String GRID = "GRID_ID";
    private static final String CHILDREN = "children";
    private static final String ORIGIN_COLUMN = "data_origin";
    private static final Map<String, String> ORIGIN_LABEL =
            Map.of(
                    "expressionType", "SQL",
                    "sqlExpression",
                            "if(data_origin = 'demo', 'Демонстрационные (demo)', 'Рабочие (live)')",
                    "label", "Источник данных",
                    "columnType", "GROUPBY");

    enum Chart {
        MONEY(
                "financial_daily",
                "Движение денег по дням",
                List.of("cash_in", "cash_out", "transfers"),
                List.of(),
                true),
        OPERATIONS(
                "financial_daily",
                "Завершённые операции",
                List.of("operations", "accounts"),
                List.of(),
                true),
        ACTIONS(
                "engagement_daily",
                "Действия в кабинете",
                List.of("events"),
                List.of("source", "event_type", "section"),
                false),
        ACTIVITY("engagement_daily", "Активность по дням", List.of("events"), List.of(), true),
        DELIVERY(
                "notification_daily",
                "Доставка и прочтение",
                List.of("available", "read"),
                List.of(),
                true),
        NOTIFICATIONS(
                "notification_daily",
                "Уведомления по дням",
                List.of("available", "read"),
                List.of("date"),
                false);

        final String table;
        final String title;
        final List<String> metrics;
        final List<String> groups;
        final boolean timeSeries;

        Chart(
                String table,
                String title,
                List<String> metrics,
                List<String> groups,
                boolean timeSeries) {
            this.table = table;
            this.title = title;
            this.metrics = metrics;
            this.groups = groups;
            this.timeSeries = timeSeries;
        }
    }

    record Dashboard(String slug, String title, String identityKey, List<Chart> charts) {}

    static final List<Dashboard> DASHBOARDS =
            List.of(
                    new Dashboard(
                            "bank-activity",
                            "Банк: операции и взаимодействие",
                            "dashboard",
                            List.of(Chart.values())),
                    new Dashboard(
                            "bank-financial",
                            "Банк: финансовые операции",
                            "financial-dashboard",
                            List.of(Chart.MONEY, Chart.OPERATIONS)));

    private SupersetAssets() {}

    static byte[] archive(String host) throws IOException {
        if (!host.matches("[a-zA-Z0-9.-]+")) {
            throw new IllegalArgumentException("Invalid BI database host");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            // JSON — подмножество YAML: не добавляем отдельный parser ради vendor-архива.
            entry(zip, "metadata.yaml", Map.of(VERSION_FIELD, VERSION, "type", "assets"));
            entry(zip, DATABASE_FILE, database(host));
            List<Map<String, Object>> charts = new ArrayList<>();
            dataset(
                    zip,
                    "financial_daily",
                    List.of(
                            "date",
                            ORIGIN_COLUMN,
                            "incoming_minor",
                            "outgoing_minor",
                            "transfer_minor",
                            "operations_count",
                            "accounts_count"),
                    Map.of(
                            "cash_in",
                            "sum(incoming_minor) / 100.0",
                            "cash_out",
                            "sum(outgoing_minor) / 100.0",
                            "transfers",
                            "sum(transfer_minor) / 100.0",
                            "operations",
                            "sum(operations_count)",
                            "accounts",
                            "max(accounts_count)"));
            dataset(
                    zip,
                    "engagement_daily",
                    List.of(
                            "date",
                            ORIGIN_COLUMN,
                            "source",
                            "event_type",
                            "section",
                            "events_count"),
                    Map.of("events", "sum(events_count)"));
            dataset(
                    zip,
                    "notification_daily",
                    List.of("date", ORIGIN_COLUMN, "available_count", "read_count"),
                    Map.of("available", "sum(available_count)", "read", "sum(read_count)"));
            for (Chart definition : Chart.values()) {
                charts.add(
                        chart(
                                definition.table,
                                definition.title,
                                definition.metrics,
                                definition.groups,
                                definition.timeSeries));
            }
            for (int i = 0; i < charts.size(); i++) {
                entry(zip, "charts/chart-" + i + ".yaml", charts.get(i));
            }
            for (Dashboard definition : DASHBOARDS) {
                String file =
                        definition.slug().equals("bank-activity") ? "bank" : definition.slug();
                entry(
                        zip,
                        "dashboards/" + file + ".yaml",
                        dashboard(
                                definition,
                                definition.charts().stream()
                                        .map(chart -> charts.get(chart.ordinal()))
                                        .toList()));
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, Object> database(String host) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("database_name", DATABASE_NAME);
        result.put(
                "sqlalchemy_uri",
                "clickhousedb://bank_bi:XXXXXXXXXX@" + host + ":8123/bank_analytics");
        result.put("uuid", DATABASE_ID);
        result.put(VERSION_FIELD, VERSION);
        result.put("expose_in_sqllab", false);
        result.put("allow_run_async", false);
        result.put("allow_dml", false);
        result.put("allow_ctas", false);
        result.put("allow_cvas", false);
        result.put("allow_file_upload", false);
        // Лимиты задаёт профиль ClickHouse: settings в connect_args дублирует аргумент DBAPI.
        result.put("extra", Map.of());
        return result;
    }

    private static void dataset(
            ZipOutputStream zip, String table, List<String> columns, Map<String, String> metrics)
            throws IOException {
        List<Map<String, Object>> fields =
                columns.stream()
                        .map(
                                name ->
                                        Map.<String, Object>of(
                                                "column_name",
                                                name,
                                                "verbose_name",
                                                name.equals(ORIGIN_COLUMN)
                                                        ? "Источник данных"
                                                        : name,
                                                "is_dttm",
                                                name.equals("date"),
                                                "type",
                                                name.equals("date")
                                                        ? "DATE"
                                                        : name.endsWith("_minor")
                                                                        || name.endsWith("_count")
                                                                ? "BIGINT"
                                                                : "STRING",
                                                "groupby",
                                                true,
                                                "filterable",
                                                true))
                        .toList();
        List<Map<String, Object>> measures =
                metrics.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(
                                metric ->
                                        Map.<String, Object>of(
                                                "metric_name",
                                                metric.getKey(),
                                                "expression",
                                                metric.getValue()))
                        .toList();
        entry(
                zip,
                "datasets/bank/" + table + ".yaml",
                Map.of(
                        "table_name",
                        table,
                        "schema",
                        "bank_analytics",
                        "main_dttm_col",
                        "date",
                        "uuid",
                        identity(table),
                        "database_uuid",
                        DATABASE_ID,
                        VERSION_FIELD,
                        VERSION,
                        "columns",
                        fields,
                        "metrics",
                        measures,
                        "description",
                        "Дневные агрегаты за 30 UTC-дней; demo - демонстрационные данные, live - рабочие события"));
    }

    private static Map<String, Object> chart(
            String table,
            String title,
            List<String> metrics,
            List<String> groups,
            boolean timeSeries)
            throws IOException {
        String visualization = timeSeries ? "echarts_timeseries_line" : "table";
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("viz_type", visualization);
        params.put("metrics", metrics);
        List<Object> dimensions = new ArrayList<>(groups);
        dimensions.add(ORIGIN_LABEL);
        params.put("groupby", dimensions);
        params.put("time_range", "No filter");
        params.put("granularity_sqla", "date");
        params.put("time_grain_sqla", "P1D");
        params.put("row_limit", 1000);
        params.put("show_legend", true);
        // В новом стенде данные одного дня: без маркера линия из одной точки не видна.
        if (timeSeries) {
            params.put("markerEnabled", true);
        }
        params.put("query_mode", "aggregate");
        params.put("x_axis", "date");
        params.put("seriesType", "line");
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("time_range", "No filter");
        query.put("granularity", "date");
        query.put(
                "columns",
                timeSeries
                        ? List.of(
                                Map.of(
                                        "timeGrain",
                                        "P1D",
                                        "columnType",
                                        "BASE_AXIS",
                                        "sqlExpression",
                                        "date",
                                        "label",
                                        "date",
                                        "expressionType",
                                        "SQL"),
                                ORIGIN_LABEL)
                        : dimensions);
        query.put("metrics", metrics);
        query.put("row_limit", 1000);
        query.put("is_timeseries", timeSeries);
        query.put("extras", Map.of("time_grain_sqla", "P1D"));
        return Map.of(
                "slice_name",
                title,
                "viz_type",
                visualization,
                "uuid",
                identity(title),
                VERSION_FIELD,
                VERSION,
                "dataset_uuid",
                identity(table),
                "params",
                params,
                "query_context",
                JSON.writeValueAsString(
                        Map.of(
                                "datasource",
                                Map.of("id", 1, "type", "table"),
                                "queries",
                                List.of(query),
                                "result_format",
                                "json",
                                "result_type",
                                "full")));
    }

    private static Map<String, Object> dashboard(
            Dashboard definition, List<Map<String, Object>> charts) {
        Map<String, Object> position = new LinkedHashMap<>();
        List<String> rows =
                IntStream.range(0, Math.ceilDiv(charts.size(), 2))
                        .mapToObj(row -> "ROW-" + row)
                        .toList();
        position.put("DASHBOARD_VERSION_KEY", "v2");
        position.put(ROOT, Map.of("id", ROOT, "type", "ROOT", CHILDREN, List.of(GRID)));
        position.put(
                GRID, Map.of("id", GRID, "type", "GRID", CHILDREN, rows, "parents", List.of(ROOT)));
        for (int row = 0; row < rows.size(); row++) {
            String rowId = rows.get(row);
            position.put(
                    rowId,
                    Map.of(
                            "id",
                            rowId,
                            "type",
                            "ROW",
                            CHILDREN,
                            IntStream.range(row * 2, Math.min(row * 2 + 2, charts.size()))
                                    .mapToObj(index -> "CHART-" + index)
                                    .toList(),
                            "parents",
                            List.of(ROOT, GRID),
                            "meta",
                            Map.of("background", "BACKGROUND_TRANSPARENT")));
            for (int column = 0; column < Math.min(2, charts.size() - row * 2); column++) {
                int index = row * 2 + column;
                Map<String, Object> chart = charts.get(index);
                String id = "CHART-" + index;
                position.put(
                        id,
                        Map.of(
                                "id",
                                id,
                                "type",
                                "CHART",
                                CHILDREN,
                                List.of(),
                                "parents",
                                List.of(ROOT, GRID, rowId),
                                "meta",
                                Map.of(
                                        "chartId",
                                        index + 1,
                                        "uuid",
                                        chart.get("uuid"),
                                        "width",
                                        6,
                                        "height",
                                        50,
                                        "sliceName",
                                        chart.get("slice_name"))));
            }
        }
        return Map.of(
                "dashboard_title",
                definition.title(),
                "slug",
                definition.slug(),
                "uuid",
                identity(definition.identityKey()),
                VERSION_FIELD,
                VERSION,
                "published",
                true,
                "position",
                position,
                "metadata",
                Map.of("refresh_frequency", 0, "color_scheme", "supersetColors"));
    }

    static String identity(String name) {
        return UUID.nameUUIDFromBytes(("bank-bi-v1:" + name).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static void entry(ZipOutputStream zip, String name, Object value) throws IOException {
        ZipEntry entry = new ZipEntry("bank/" + name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(JSON.writeValueAsBytes(value));
        zip.closeEntry();
    }
}
