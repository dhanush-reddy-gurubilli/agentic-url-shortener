package com.agentic.orchestrator;

import com.agentic.orchestrator.tool.CodeIndex;
import com.agentic.orchestrator.tool.CodeIndex.Index;
import com.agentic.orchestrator.tool.WorkspaceFs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeIndexTest {

    @TempDir
    Path root;
    WorkspaceFs fs;

    private void write(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeEach
    void setUp() throws Exception {
        String base = "src/main/java/com/agentic/shortener/";
        write(base + "web/UrlController.java", """
                package com.agentic.shortener.web;
                import com.agentic.shortener.service.ShortenerService;
                @RestController
                @RequestMapping("/api/v1/urls")
                public class UrlController {
                    @PostMapping
                    public ResponseEntity<UrlResponse> create(CreateUrlRequest r) { return null; }
                    @GetMapping("/{code}/stats")
                    public Stats stats(String code) { return null; }
                    @DeleteMapping(value = "/{code}")
                    public void delete(String code) { }
                }
                """);
        write(base + "service/ShortenerService.java", """
                package com.agentic.shortener.service;
                import com.agentic.shortener.domain.ShortUrlRepository;
                public class ShortenerService {
                    public Object resolve(String code) { /* expiry is checked on cache load */ return null; }
                }
                """);
        write(base + "domain/ShortUrl.java", """
                package com.agentic.shortener.domain;
                @Entity
                @Table(name = "urls")
                public class ShortUrl { }
                """);
        write("src/main/resources/db/migration/V1__shortener.sql", "create table urls ();");
        write("src/main/resources/db/migration/V2__orchestrator.sql", "create table x ();");
        fs = new WorkspaceFs(root);
    }

    @Test
    void indexesClassesEndpointsTablesAndMigrations() {
        Index index = CodeIndex.build(fs);

        assertThat(index.classes()).extracting(CodeIndex.ClassInfo::name)
                .containsExactlyInAnyOrder("UrlController", "ShortenerService", "ShortUrl");
        assertThat(index.endpoints()).extracting(e -> e.verb() + " " + e.path() + " -> " + e.handler())
                .containsExactlyInAnyOrder("POST /api/v1/urls -> create", "GET /api/v1/urls/{code}/stats -> stats",
                        "DELETE /api/v1/urls/{code} -> delete");
        assertThat(index.tables()).containsExactly("urls");
        assertThat(index.migrations()).containsExactly("V1__shortener.sql", "V2__orchestrator.sql");
    }

    @Test
    void recordsWhichClassesEachClassDependsOn() {
        Index index = CodeIndex.build(fs);
        assertThat(index.classes().stream().filter(c -> c.name().equals("UrlController")).findFirst().orElseThrow().dependsOn())
                .containsExactly("ShortenerService");
        assertThat(index.classes().stream().filter(c -> c.name().equals("ShortenerService")).findFirst().orElseThrow().dependsOn())
                .containsExactly("ShortUrlRepository");
    }

    @Test
    void relevanceRanksTheClassThatMentionsTheKeywordsMost() {
        Index index = CodeIndex.build(fs);
        List<CodeIndex.ClassInfo> ranked = CodeIndex.relevant(fs, index, List.of("expiry", "cache"));
        assertThat(ranked).extracting(CodeIndex.ClassInfo::name).containsExactly("ShortenerService");
        assertThat(CodeIndex.relevant(fs, index, List.of("nothing-matches-this"))).isEmpty();
    }

    @Test
    void anEmptyWorkspaceIndexesToNothing() throws Exception {
        Path empty = Files.createTempDirectory(root, "empty");
        Index index = CodeIndex.build(new WorkspaceFs(empty));
        assertThat(index.classes()).isEmpty();
        assertThat(index.endpoints()).isEmpty();
    }
}
