package dev.principalwater.study.report;

import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class TestReport {
    record Result(String name, boolean failed) {
        String status() { return failed ? "FAILED" : "PASSED"; }
    }

    private static final Pattern CASE = Pattern.compile("^(.+?)\\s+Time elapsed:.*$");

    static List<Result> read(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) throw new IOException("Каталог отчётов не найден: " + directory);
        List<Path> files;
        try (var entries = Files.list(directory)) {
            files = entries.filter(Files::isRegularFile).sorted().toList();
        }
        var xmlFiles = files.stream().filter(p -> p.getFileName().toString().startsWith("TEST-")
                && p.toString().endsWith(".xml")).toList();
        List<Result> results = new ArrayList<>();
        if (!xmlFiles.isEmpty()) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            for (Path file : xmlFiles) {
                var cases = factory.newDocumentBuilder().parse(file.toFile()).getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    var test = (Element) cases.item(i);
                    String name = test.getAttribute("classname") + "#" + test.getAttribute("name");
                    if (test.getAttribute("name").isBlank()) throw new IOException("Тест без имени: " + file);
                    boolean failed = test.getElementsByTagName("failure").getLength() > 0
                            || test.getElementsByTagName("error").getLength() > 0;
                    results.add(new Result(name, failed));
                }
            }
        } else {
            // Учебный TXT перечисляет все методы; реальный краткий TXT Surefire — обычно только сбои.
            for (Path file : files.stream().filter(p -> p.toString().endsWith(".txt")).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.startsWith("Tests run:")) continue;
                    var matcher = CASE.matcher(line);
                    if (matcher.matches()) results.add(new Result(matcher.group(1).trim(),
                            line.contains("<<< FAILURE!") || line.contains("<<< ERROR!")));
                }
            }
        }
        if (results.isEmpty()) throw new IOException("Отчёты не содержат результатов тестов: " + directory);
        return List.copyOf(results);
    }

    static void write(List<Result> results, Path output) throws IOException {
        String fileName = output.getFileName().toString();
        String stem = fileName.replaceFirst("\\.(html|txt)$", "");
        Path html = output.resolveSibling(stem + ".html");
        Path text = output.resolveSibling(stem + ".txt");
        var body = new StringBuilder("<!doctype html><html lang=\"ru\"><meta charset=\"utf-8\"><title>Результаты тестов</title>")
                .append("<style>body{font:17px system-ui;max-width:960px;margin:40px auto;padding:0 20px}table{width:100%;border-collapse:collapse}td,th{padding:10px;text-align:left;border-bottom:1px solid #ddd}.FAILED{color:#b21c30}.PASSED{color:#087443}</style>")
                .append("<h1>Результаты тестов</h1><table><tr><th>Тест</th><th>Статус</th></tr>");
        var plain = new StringBuilder();
        for (Result result : results) {
            body.append("<tr><td>").append(escape(result.name())).append("</td><td class=\"")
                    .append(result.status()).append("\">").append(result.status()).append("</td></tr>");
            plain.append(result.name().replace("\r", "\\r").replace("\n", "\\n"))
                    .append(": ").append(result.status()).append('\n');
        }
        writeAtomic(html, body.append("</table></html>").toString());
        writeAtomic(text, plain.toString());
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void writeAtomic(Path output, String contents) throws IOException {
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".test-report-", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
