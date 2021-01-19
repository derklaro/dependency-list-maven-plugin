/*
 * This file is licensed under the MIT License (MIT).
 *
 * Copyright (c) 2020 - 2021 Pasqual K.
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
package com.github.derklaro;

import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
@Mojo(name = "dependency-list", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
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
  private final ExecutorService lookupPool = Executors.newCachedThreadPool();
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
   * <code>{4}</code> is the output of the repository url the dependency is located in
   * <code>{5}</code> is the output of the repository id the dependency is located in
   */
  @Parameter(defaultValue = "{0}:{1}:{2}")
  private String outputFormat;
  /**
   * If the plugin should include optional dependencies
   */
  @Parameter(defaultValue = "false")
  private boolean includeOptionalDependencies;
  /**
   * If the plugin should create the parent directories if they do not exists
   */
  @Parameter(defaultValue = "true")
  private boolean createParentFiles;
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

    File resultFile = new File(this.outputFileName);
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

    Path parent = resultFile.toPath().getParent();
    if (parent != null && !parent.toFile().exists() && this.createParentFiles) {
      try {
        Files.createDirectories(parent);
      } catch (final IOException ex) {
        this.printWarningOrFail("Error creating parent directory " + parent.toFile().getAbsolutePath());
        return;
      }
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

    try {
      Files.write(resultFile.toPath(), this.collectDependencyArtifacts(this.selectScopes()).getBytes(StandardCharsets.UTF_8));
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
  private String collectDependencyArtifacts(Set<String> allowedScopes) throws MojoExecutionException {
    final StringBuilder stringBuilder = new StringBuilder();

    final Collection<Artifact> artifacts;
    if (this.resolveDependenciesOfDependencies) {
      artifacts = this.project.getArtifacts();
      for (Artifact artifact : artifacts) {
        this.getLog().info(artifact.getGroupId() + ":" + artifact.getArtifactId());
      }
    } else {
      artifacts = new ArrayList<>();

      for (Dependency dependency : this.project.getDependencies()) {
        Artifact artifact = this.project.getArtifactMap().get(dependency.getGroupId() + ':' + dependency.getArtifactId());
        if (artifact != null) {
          artifacts.add(artifact);
        }
      }
    }

    // Sort all ignored artifacts out
    artifacts.removeIf(artifact -> !allowedScopes.contains(artifact.getScope()) || this.isIgnored(artifact));

    // Already travelled dependencies in format 'group-id':'artifact-id' to prevent duplicates
    Set<String> travelled = new HashSet<>();

    // No repository lookup is required because it's not needed
    if (!this.outputFormat.contains("{4}") && !this.outputFormat.contains("{5}")) {
      artifacts.forEach(artifact -> {
        String formatted = artifact.getGroupId() + ':' + artifact.getArtifactId();
        if (travelled.contains(formatted)) {
          return;
        }

        travelled.add(formatted);
        stringBuilder.append(MessageFormat.format(
          this.outputFormat,
          // artifact info
          artifact.getGroupId(),
          artifact.getArtifactId(),
          artifact.getVersion(),
          artifact.getScope()
        )).append("\n");
      });
      return stringBuilder.toString();
    }

    Map<Artifact, ArtifactRepository> repositoryMap = new ConcurrentHashMap<>();
    for (Artifact artifact : artifacts) {
      Collection<RunningRepositoryLookup> lookups = this.resolveDependencyRepository(artifact);
      for (RunningRepositoryLookup lookup : lookups) {
        lookup.getLookupStatus().thenAccept(result -> {
          if (result) {
            for (RunningRepositoryLookup runningRepositoryLookup : lookups) {
              if (!runningRepositoryLookup.getLookupStatus().isDone()) {
                runningRepositoryLookup.getLookupStatus().cancel(true);
                runningRepositoryLookup.getPoolFuture().cancel(true);
              }
            }

            repositoryMap.put(artifact, lookup.getRepository());
          }
        });
      }
    }

    try {
      this.lookupPool.shutdown();
      if (!this.lookupPool.awaitTermination(5, TimeUnit.MINUTES)) {
        this.printWarningOrFail("Unable to shutdown lookup pool after 5 minutes");
      }
    } catch (InterruptedException exception) {
      this.printWarningOrFail("Unable to await shutdown of thread pool: " + exception.getMessage());
      return "";
    }

    repositoryMap.forEach((artifact, repository) -> {
      String formatted = artifact.getGroupId() + ':' + artifact.getArtifactId();
      if (travelled.contains(formatted)) {
        return;
      }

      travelled.add(formatted);
      stringBuilder.append(MessageFormat.format(
        this.outputFormat,
        // artifact info
        artifact.getGroupId(),
        artifact.getArtifactId(),
        artifact.getVersion(),
        artifact.getScope(),
        // repo info
        repository.getUrl().endsWith("/")
          ? StringUtils.removeEnd(repository.getUrl(), "/")
          : repository.getUrl(),
        repository.getId()
      )).append("\n");
    });
    return stringBuilder.toString();
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

    this.getLog().warn(message);
  }

  private Collection<RunningRepositoryLookup> resolveDependencyRepository(Artifact artifact) {
    Collection<RunningRepositoryLookup> lookups = new ArrayList<>();
    for (ArtifactRepository repository : this.project.getRemoteArtifactRepositories()) {
      lookups.add(this.lookupRepository(repository, artifact));
    }

    return lookups;
  }

  private RunningRepositoryLookup lookupRepository(ArtifactRepository repository, Artifact artifact) {
    RunningRepositoryLookup lookup = new RunningRepositoryLookup(new CompletableFuture<>(), repository);
    lookup.poolFuture = this.lookupPool.submit(() -> {
      try {
        HttpURLConnection connection = (HttpURLConnection) new URL(repository.getUrl()
          + (repository.getUrl().endsWith("/") ? "" : "/")
          + artifact.getGroupId().replace(".", "/")
          + "/" + artifact.getArtifactId()
          + "/" + artifact.getVersion()
          + "/" + artifact.getArtifactId() + "-" + artifact.getVersion() + ".jar"
        ).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setUseCaches(false);
        connection.setRequestProperty(
          "User-Agent",
          "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11"
        );
        connection.connect();

        lookup.getLookupStatus().complete(connection.getResponseCode() == 200);
      } catch (IOException exception) {
        lookup.getLookupStatus().completeExceptionally(exception);
      }
    });
    return lookup;
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

  private static final class RunningRepositoryLookup {

    private final CompletableFuture<Boolean> lookupStatus;
    private final ArtifactRepository repository;
    private Future<?> poolFuture;

    private RunningRepositoryLookup(CompletableFuture<Boolean> lookupStatus, ArtifactRepository repository) {
      this.lookupStatus = lookupStatus;
      this.repository = repository;
    }

    public CompletableFuture<Boolean> getLookupStatus() {
      return this.lookupStatus;
    }

    public ArtifactRepository getRepository() {
      return this.repository;
    }

    public Future<?> getPoolFuture() {
      return this.poolFuture;
    }
  }
}
