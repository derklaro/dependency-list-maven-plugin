# Maven dependency list plugin 

[![Discord](https://img.shields.io/discord/499666347337449472.svg?color=7289DA&label=discord)](https://discord.gg/uskXdVZ)

A simple plugin which collects all dependencies from the current project and writes 
them to the specified file.

## Example output

An output looks like this if you don't change any config options:

```
org.bouncycastle:bcprov-jdk15on:1.64
org.spigotmc:spigot-api:1.8.8-R0.1-20160221.082514-43
```

If we enable the config option `resolveDependenciesOfDependencies` it looks like this:

```
org.bouncycastle:bcprov-jdk15on:1.64
com.google.code.gson:gson:2.2.4
javax.persistence:persistence-api:1.0
org.hamcrest:hamcrest-core:1.1
junit:junit:4.10
com.google.guava:guava:17.0
com.googlecode.json-simple:json-simple:1.1.1
net.md-5:bungeecord-chat:1.8-20160221.214602-128
org.avaje:ebean:2.8.1
commons-lang:commons-lang:2.6
org.yaml:snakeyaml:1.15
org.spigotmc:spigot-api:1.8.8-R0.1-20160221.082514-43
```

or with the repository provided in the output format (see below for more information) it can look
 like:
```
central => https://repo.maven.apache.org/maven2 com.h2database:h2:1.4.200
central => https://repo.maven.apache.org/maven2 io.netty:netty-transport-native-unix-common:4.1.52.Final
jitpack.io => https://jitpack.io com.github.derrop:documents:1.1-RELEASE
```

## Repository

You have to use a plugin repository as shown below to use the plugin:
```xml
    <pluginRepositories>
        <pluginRepository>
            <id>reformcloud</id>
            <url>https://repo.reformcloud.systems/</url>
        </pluginRepository>
    </pluginRepositories>
```

## Example Configuration
```xml
        <plugins>
            <plugin>
                <groupId>de.derklaro</groupId>
                <artifactId>dependency-list-maven-plugin</artifactId>
                <version>1.3.0</version>
                <configuration>
                    <!-- The full path of the output file, viewed from the current project folder -->
                    <outputFileName>src/main/resources/depends.txt</outputFileName>

                    <!-- The excluded artifacts files -->
                    <excludes>
                        <!-- Excludes all files with the 'org.spigotmc' group id -->
                        <exclude>org.spigotmc:*</exclude>
                        <!-- Excludes all files with the 'spigot-api' artifact id -->
                        <exclude>*:spigot-api</exclude>
                        <!-- Excludes the org.spigotmc:spigot-api dependency -->
                        <exclude>org.spigotmc:spigot-api</exclude>
                    </excludes>

                    <!-- The excluded dependency scopes -->
                    <excludedScopes>
                        <!-- Excludes the 'provided' dependencies -->
                        <excludedScope>provided</excludedScope>
                        <!-- Excludes the 'test' dependencies -->
                        <excludedScope>test</excludedScope>
                    </excludedScopes>

                    <!-- If the build should fail if an error occurs or just print an warning -->
                    <fail>true</fail>

                    <!-- If the output file already exists - should the plugin override it? -->
                    <overrideExistingFile>true</overrideExistingFile>

                    <!-- If the plugin should resolve the build-in dependencies of the project dependencies -->
                    <resolveDependenciesOfDependencies>false</resolveDependenciesOfDependencies>

                    <!-- If the plugin should resolve the dependencies which are clearly marked as optional -->                    
                    <includeOptionalDependencies>false</includeOptionalDependencies>

                    <!-- If the plugin should generate the parent directories of the output file -->
                    <createParentFiles>true</createParentFiles>

                    <!-- 
                    Sets the output format for every line in the output file:
                         {0} is the output of the groupId
                         {1} is the output of the artifactId
                         {2} is the output of the version
                         {3} is the output of the dependency scope
                         {4} is the output of the repository remote url the dependency is located in
                         {5} is the output of the repository remote id the dependency is located in
                    -->
                    <outputFormat>{0}:{1}:{2} - {3} IN {4}/{5}</outputFormat>
                </configuration>
                <executions>
                    <execution>
                        <goals>
                            <goal>dependency-list</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
```
