package dev.principalwater.study.report;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;

@Mojo(name = "report", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public final class ReportMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}/surefire-reports", property = "reportDirectory", required = true)
    private File reportDirectory;

    @Parameter(defaultValue = "${project.build.directory}/test-report.html", property = "outputFile", required = true)
    private File outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Path output = outputFile.toPath().toAbsolutePath().normalize();
            var results = TestReport.read(reportDirectory.toPath());
            TestReport.write(results, output);
            getLog().info("Generated test reports for " + results.size() + " tests: " + output);
        } catch (Exception error) {
            throw new MojoExecutionException("Не удалось сформировать отчёт: " + error.getMessage(), error);
        }
    }
}
