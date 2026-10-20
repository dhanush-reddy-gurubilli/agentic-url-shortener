package com.agentic.orchestrator.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A tiny stand-in for the repository an orchestrator run works on (the engine tests never compile it). */
public final class FixtureRepo {

    private static Path path;

    private FixtureRepo() {
    }

    /** Created once per JVM; each run copies it into its own workspace. */
    public static synchronized Path path() {
        if (path == null) {
            try {
                path = Files.createTempDirectory("agentic-fixture-repo");
                write("pom.xml", "<project>\n  <dependencies>\n    <dependency>\n      <groupId>org.springframework.boot</groupId>\n"
                        + "      <artifactId>spring-boot-starter-webmvc</artifactId>\n    </dependency>\n  </dependencies>\n</project>\n");
                write("README.md", "# Fixture\n\n## Overview\n\nA tiny repository used by engine tests.\n");
                write("src/main/java/com/agentic/shortener/service/ShortenerService.java",
                        "package com.agentic.shortener.service;\n\npublic class ShortenerService {\n"
                                + "    public String resolve(String code) {\n        return code;\n    }\n}\n");
                write("src/main/java/com/agentic/shortener/web/UrlController.java",
                        "package com.agentic.shortener.web;\n\n@RestController\n@RequestMapping(\"/api/v1/urls\")\npublic class UrlController {\n"
                                + "    @GetMapping(\"/{code}\")\n    public String get(String code) {\n        return code;\n    }\n}\n");
                write("src/main/resources/db/migration/V1__init.sql", "create table urls (id bigint primary key);\n");
                write("src/test/java/com/agentic/shortener/service/ShortenerServiceTest.java",
                        "package com.agentic.shortener.service;\n\nclass ShortenerServiceTest {\n}\n");
                write("src/main/java/com/agentic/orchestrator/Engine.java", "package com.agentic.orchestrator;\n\nclass Engine {\n}\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return path;
    }

    private static void write(String relative, String content) throws IOException {
        Path file = path.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
