package org.testwatch.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.MavenSession;

import java.util.List;

/**
 * Goal: test-watch:watch
 * Starts watching src/ for changes immediately. No tests run until the first
 * file change.
 */
@Mojo(name = "watch", requiresProject = true, aggregator = true)
public class WatchMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "testWatch.includes", defaultValue = "**/*.java")
    private List<String> includes;

    @Parameter(property = "testWatch.excludes", defaultValue = "**/target/**")
    private List<String> excludes;

    @Parameter(property = "testWatch.testPattern", defaultValue = "**/Test*.java,**/*Test.java,**/*Tests.java,**/*TestCase.java")
    private String testPattern;

    /** Opt-in method parallelism; otherwise retain the project's test execution settings. */
    @Parameter(property = "testWatch.parallel", defaultValue = "false")
    private boolean parallel;

    @Parameter(property = "testWatch.smartSelection", defaultValue = "true")
    private boolean smartSelection;

    @Parameter(property = "testWatch.debounceMillis", defaultValue = "100")
    private long debounceMillis;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            new WatchLoop(project, session, false, smartSelection, parallel,
                    includes, excludes, testPattern, debounceMillis).run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new MojoExecutionException("test-watch:watch failed", e);
        }
    }
}
