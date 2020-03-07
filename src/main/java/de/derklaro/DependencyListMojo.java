/*
 * This file is licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020 Pasqual K.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.derklaro;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Mojo(name = "dependency-list", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE)
public final class DependencyListMojo extends AbstractMojo {

    /**
     * All available scopes provided by maven for dependencies
     */
    private static final Collection<String> SCOPES = Arrays.asList(
            Artifact.SCOPE_COMPILE,
            Artifact.SCOPE_PROVIDED,
            Artifact.SCOPE_RUNTIME,
            Artifact.SCOPE_IMPORT,
            Artifact.SCOPE_SYSTEM,
            Artifact.SCOPE_TEST
    );

    /**
     * Maven project on which the plugin is operating
     */
    @Parameter(property = "project", readonly = true, required = true)
    private MavenProject project;

    /**
     * The output file name including the directory
     */
    @Parameter(defaultValue = "dependency-list.txt")
    private String outputFileName;

    /**
     * If the build should fail if an error occurs
     */
    @Parameter(defaultValue = "false")
    private boolean fail;

    /**
     * If the plugin should override the file if it already exists
     */
    @Parameter(defaultValue = "true")
    private boolean overrideExistingFile;

    /**
     * If the plugin should also resolve the dependencies of the dependencies
     */
    @Parameter(defaultValue = "false")
    private boolean resolveDependenciesOfDependencies;

    /**
     * Sets the output format for the dependency.
     *
     * <code>{0}</code> is the output of the groupId
     * <code>{1}</code> is the output of the artifactId
     * <code>{2}</code> is the output of the version
     * <code>{3}</code> is the output of the dependency scope
     */
    @Parameter(defaultValue = "{0}:{1}:{2}")
    private String outputFormat;

    /**
     * Artifacts which should get excluded from the dependency tree building. Based on the identifier
     * in the general form <code>groupId:artifactId</code>. The version of the dependency is ignored.
     * It's also possible to ignore whole artifact name by using <code>groupId:*</code> or all dependencies
     * with the same groupId: <code>*:artifactId</code>
     *
     * <pre>
     *     &lt;excludes&gt;
     *      &lt;exclude&gt;de.derklaro:project-name&lt;/exclude&gt;
     *     &lt;/excludes&gt;
     * </pre>
     */
    @Parameter
    private Set<String> excludes;

    /**
     * The scopes of dependencies which should get ignored. If the list contains for example <code>provided</code>,
     * all dependencies which are marked as provided are ignored. If the option {@link DependencyListMojo#resolveDependenciesOfDependencies}
     * is <code>true</code> also all dependency scopes of the dependencies will be ignored.
     *
     * <pre>
     *     &lt;excludedScopes&gt;
     *      &lt;excludedScope&gt;compile&lt;/exclude&gt;
     *      &lt;excludedScope&gt;runtime&lt;/exclude&gt;
     *     &lt;/excludedScopes&gt;
     * </pre>
     */
    @Parameter
    private Set<String> excludedScopes;

    @Override
    public void execute() throws MojoExecutionException {
        this.debugValues();

        File resultFile = new File(outputFileName);
        if (resultFile.exists()) {
            this.getLog().debug("The resultFile already exists at " + resultFile.getAbsolutePath());

            if (!this.overrideExistingFile) {
                this.printWarningOrFail("The output file already exists and it's define to not override " +
                        "the existing file at " + resultFile.getAbsolutePath());
                return;
            }
        }

        if (this.excludes == null) {
            this.getLog().debug("Setting default values for excluded artifacts");
            this.excludes = new HashSet<>();
        }

        if (this.excludedScopes == null) {
            this.getLog().debug("Setting default values for excluded scopes");
            this.excludedScopes = new HashSet<>();
        }

        try {
            Files.deleteIfExists(resultFile.toPath());
            Files.createFile(resultFile.toPath());
            this.getLog().debug("Re-created old output file");
        } catch (final IOException ex) {
            this.printWarningOrFail("Error deleting old file or creating new one: " + ex.getMessage());
            return;
        }

        if (resultFile.isDirectory()) {
            this.printWarningOrFail("Cannot write to directory");
            return;
        }

        Set<String> out = new HashSet<>();
        Set<Artifact> artifacts = this.dependencyArtifacts();
        for (Artifact artifact : artifacts) {
            if (this.isIgnored(artifact)) {
                this.getLog().info("Ignoring " + artifact.getGroupId() + ":" + artifact.getArtifactId());
                continue;
            }

            this.getLog().info("Including " + artifact.getGroupId() + ":" + artifact.getArtifactId());
            out.add(MessageFormat.format(
                    this.outputFormat,
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getVersion(),
                    artifact.getScope()
            ));
        }

        try {
            Files.write(resultFile.toPath(), out, StandardCharsets.UTF_8);
            this.getLog().info("Wrote dependency list to " + resultFile.getAbsolutePath());
        } catch (final IOException ex) {
            this.printWarningOrFail("Failed to write dependencies to file " + resultFile.getAbsolutePath() + " error: " + ex.getMessage());
        }
    }

    /**
     * Removes from all available scopes ({@link DependencyListMojo#SCOPES}) the disabled scopes
     *
     * @return All enabled scopes for the dependency list
     * @see DependencyListMojo#excludedScopes
     */
    private Set<String> selectScopes() {
        Set<String> set = new HashSet<>(SCOPES);
        set.removeAll(this.excludedScopes);
        return set;
    }

    /**
     * Filters from all dependencies the disabled out
     *
     * @return A set of all dependencies which should get written
     * @see DependencyListMojo#excludedScopes
     * @see DependencyListMojo#resolveDependenciesOfDependencies
     */
    private Set<Artifact> dependencyArtifacts() {
        Set<Artifact> out = new HashSet<>();
        Set<String> allowedScopes = selectScopes();
        for (Object dependencyArtifact : this.resolveDependenciesOfDependencies ? this.project.getArtifacts() : this.project.getDependencyArtifacts()) {
            if (dependencyArtifact instanceof Artifact) {
                Artifact artifact = (Artifact) dependencyArtifact;
                if (!allowedScopes.contains(artifact.getScope())) {
                    continue;
                }

                out.add(artifact);
            }
        }

        return out;
    }

    /**
     * Checks if the specified artifact is ignored by the user configuration
     *
     * @param artifact The artifact which should be checked if it's ignored
     * @return If the artifact is ignored
     */
    private boolean isIgnored(Artifact artifact) {
        boolean generalIgnored = this.excludes.contains(artifact.getGroupId() + ":" + artifact.getArtifactId());
        boolean groupIdIgnored = this.excludes.contains(artifact.getGroupId() + ":*");
        boolean artifactIdIgnored = this.excludes.contains("*:" + artifact.getArtifactId());
        return generalIgnored || groupIdIgnored || artifactIdIgnored;
    }

    /**
     * Prints a warning or (if enabled see {@link DependencyListMojo#fail}) throws a mojo execution
     * exception with the specified message
     *
     * @param message The message which should get printed
     * @throws MojoExecutionException If enabled, it will not print the warning but throws this exception
     */
    private void printWarningOrFail(String message) throws MojoExecutionException {
        if (this.fail) {
            throw new MojoExecutionException(message);
        }

        getLog().warn(message);
    }

    /**
     * Debugs the runtime values to the console (If debug is enabled)
     */
    private void debugValues() {
        this.getLog().debug("Output file name                     : " + this.outputFileName);
        this.getLog().debug("Fail on on error                     : " + this.fail);
        this.getLog().debug("Resolve dependencies of dependencies : " + this.resolveDependenciesOfDependencies);
        this.getLog().debug("Override existing file               : " + this.overrideExistingFile);
        this.getLog().debug("Output string format                 : " + this.outputFormat);
        this.getLog().debug("Excluded                             : " + (this.excludes == null ? "none" : String.join(", ", this.excludes)));
        this.getLog().debug("Excluded scopes                      : " + (this.excludedScopes == null ? "none" : String.join(", ", this.excludedScopes)));
    }
}
