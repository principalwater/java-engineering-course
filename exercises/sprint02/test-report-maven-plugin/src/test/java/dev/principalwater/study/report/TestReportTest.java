package dev.principalwater.study.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestReportTest {
    @TempDir Path directory;

    @Test void reportsEveryXmlCaseAndEscapesNamesWithoutDuplicatingTextResults() throws Exception {
        Files.writeString(directory.resolve("TEST-example.xml"), """
                <testsuite tests="3" failures="1" errors="1">
                  <testcase classname="Example" name="ok&lt;tag&gt;&#10;continued"/>
                  <testcase classname="Example" name="failed"><failure message="broken"/></testcase>
                  <testcase classname="Example" name="error"><error message="exception"/></testcase>
                </testsuite>
                """);
        Files.writeString(directory.resolve("Example.txt"), "failed Time elapsed: 0.1 s <<< FAILURE!");
        var results = TestReport.read(directory);
        TestReport.write(results, directory.resolve("report.html"));
        assertEquals(List.of(new TestReport.Result("Example#ok<tag>\ncontinued", false),
                new TestReport.Result("Example#failed", true), new TestReport.Result("Example#error", true)), results);
        assertEquals("Example#ok<tag>\\ncontinued: PASSED\nExample#failed: FAILED\nExample#error: FAILED\n",
                Files.readString(directory.resolve("report.txt")));
        String html = Files.readString(directory.resolve("report.html"));
        assertTrue(html.contains("Example#ok&lt;tag&gt;"));
        assertFalse(html.contains("<tag>"));
    }

    @Test void readsTheEducationalTextFormatAndIgnoresItsSummary() throws Exception {
        Files.writeString(directory.resolve("TEST-example.txt"), """
                Tests run: 3, Failures: 1, Errors: 1, Skipped: 0, Time elapsed: 0.1 s
                addition Time elapsed: 0.01 s <<< FAILURE!
                java.lang.AssertionError: unexpected
                subtraction Time elapsed: 0.01 s
                division Time elapsed: 0.01 s <<< ERROR!
                """);
        assertEquals(List.of(new TestReport.Result("addition", true), new TestReport.Result("subtraction", false),
                new TestReport.Result("division", true)), TestReport.read(directory));
    }

    @Test void rejectsExternalEntitiesInsteadOfReadingFilesOutsideTheReport() throws Exception {
        Path secret = directory.resolve("private.txt");
        Files.writeString(secret, "private-test-value");
        Files.writeString(directory.resolve("TEST-hostile.xml"), """
                <!DOCTYPE testsuite [<!ENTITY external SYSTEM "%s">]>
                <testsuite><testcase classname="Example" name="safe"/><system-out>&external;</system-out></testsuite>
                """.formatted(secret.toUri()));
        var error = assertThrows(org.xml.sax.SAXParseException.class, () -> TestReport.read(directory));
        assertTrue(error.getMessage().contains("DOCTYPE"));
    }
}
