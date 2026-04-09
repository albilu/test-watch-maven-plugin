# test-watch-maven-plugin

Lightweight `Vitest` watch mode inspired Maven plugin for watching, running, and summarizing test results during development.

## Features

- Watch test sources and re-run tests based on changes.
- Produce readable summaries of test results.
- Designed for integration with Maven builds and invoker-based integration tests.

## Requirements

- Java JDK 8+

## Usage

The plugin is published under the `io.github.albilu` groupId in this repository's build metadata. To use it in your project's `pom.xml`, register the GitHub Packages repository and add a plugin entry like this:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/albilu/test-watch-maven-plugin</url>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.github.albilu</groupId>
            <artifactId>test-watch-maven-plugin</artifactId>
            <version>1.0.0</version>
            <configuration>
                <includes>
                    <include>**/*.java</include>
                </includes>
                <excludes>
                    <exclude>**/target/**</exclude>
                    <exclude>**/*Test.java</exclude>
                </excludes>
                <testPattern>**/*Test.java</testPattern>
                <parallel>true</parallel>
                <smartSelection>true</smartSelection>
            </configuration>
        </plugin>
    </plugins>
</build>
```

```bash
mvn test-watch-maven-plugin:test
```

## Contributing

Contributions welcome. Please open issues or PRs against this repository. Follow existing code style and include tests for new behavior.
