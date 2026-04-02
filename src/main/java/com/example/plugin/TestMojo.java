package com.example.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.util.List;

/**
 * Goal: test-watch:test
 * Runs the full test suite once on startup, then enters watch mode.
 */
@Mojo(name = "test", requiresProject = true)
public class TestMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "testWatch.includes", defaultValue = "**/*.java")
    private List<String> includes;

    @Parameter(property = "testWatch.excludes", defaultValue = "**/target/**")
    private List<String> excludes;

    @Parameter(property = "testWatch.testPattern", defaultValue = "**/*Test.java,**/*Tests.java")
    private String testPattern;

    @Parameter(property = "testWatch.parallel", defaultValue = "true")
    private boolean parallel;

    @Parameter(property = "testWatch.smartSelection", defaultValue = "true")
    private boolean smartSelection;

    @Parameter(property = "testWatch.debounceMillis", defaultValue = "100")
    private long debounceMillis;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            new WatchLoop(project, true, smartSelection, parallel,
                includes, excludes, testPattern, debounceMillis).run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new MojoExecutionException("test-watch:test failed", e);
        }
    }
}
