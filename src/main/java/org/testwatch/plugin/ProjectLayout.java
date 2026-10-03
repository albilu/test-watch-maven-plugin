package org.testwatch.plugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Effective paths for one project in the selected Maven reactor. */
final class ProjectLayout {
    final Path basedir;
    final Path classes;
    final Path testClasses;
    final List<Path> sources;
    final List<Path> testSources;
    final Set<Path> reports;

    ProjectLayout(MavenProject project, MavenSession session) {
        basedir = project.getBasedir().toPath().toAbsolutePath().normalize();
        Path build = resolve(project.getBuild().getDirectory(), basedir.resolve("target"));
        classes = resolve(project.getBuild().getOutputDirectory(), build.resolve("classes"));
        testClasses = resolve(project.getBuild().getTestOutputDirectory(), build.resolve("test-classes"));
        sources = roots(project.getCompileSourceRoots(), project.getBuild().getSourceDirectory(), "src/main/java");
        testSources = roots(project.getTestCompileSourceRoots(), project.getBuild().getTestSourceDirectory(), "src/test/java");
        reports = new LinkedHashSet<>();
        reports.add(build.resolve("surefire-reports"));
        for (Plugin plugin : project.getBuildPlugins()) {
            if (!"maven-surefire-plugin".equals(plugin.getArtifactId())) continue;
            addReports(plugin.getConfiguration(), plugin, project, session);
            for (PluginExecution execution : plugin.getExecutions()) {
                if (execution.getGoals().contains("test")) {
                    addReports(execution.getConfiguration(), plugin, project, session);
                }
            }
        }
    }

    private List<Path> roots(List<String> configured, String buildRoot, String fallback) {
        Set<Path> roots = new LinkedHashSet<>();
        for (String root : configured) roots.add(resolve(root, basedir.resolve(fallback)));
        if (roots.isEmpty()) roots.add(resolve(buildRoot, basedir.resolve(fallback)));
        return new ArrayList<>(roots);
    }

    private Path resolve(String value, Path fallback) {
        return value == null || value.isBlank() ? fallback : basedir.resolve(value).normalize();
    }

    private void addReports(Object configuration, Plugin plugin, MavenProject project, MavenSession session) {
        if (!(configuration instanceof Xpp3Dom)) return;
        Xpp3Dom directory = ((Xpp3Dom) configuration).getChild("reportsDirectory");
        if (directory == null || directory.getValue() == null) return;
        String value = directory.getValue();
        if (session != null) {
            MavenSession local = session.clone();
            local.setCurrentProject(project);
            try {
                Object evaluated = new PluginParameterExpressionEvaluator(local,
                        new MojoExecution(plugin, "test", "default-test")).evaluate(value);
                if (evaluated != null) value = evaluated.toString();
            } catch (ExpressionEvaluationException e) {
                throw new IllegalArgumentException("Cannot resolve Surefire reportsDirectory: " + value, e);
            }
        } else {
            value = value.replace("${project.build.directory}", resolve(project.getBuild().getDirectory(), basedir.resolve("target")).toString())
                    .replace("${project.basedir}", basedir.toString()).replace("${basedir}", basedir.toString());
        }
        if (value.contains("${")) throw new IllegalArgumentException("Unresolved Surefire reportsDirectory: " + value);
        reports.add(resolve(value, basedir.resolve("target/surefire-reports")));
    }
}
