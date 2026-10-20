package com.agentic.orchestrator.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic map of the shortener code (classes, endpoints, tables, migrations, class-to-class
 * dependencies). Brownfield impact analysis starts from this fact base, so the LLM reasons over what the code
 * actually contains rather than guessing.
 */
public final class CodeIndex {

    private static final String SOURCE_DIR = "src/main/java/com/agentic/shortener";
    private static final Pattern TYPE = Pattern.compile("\\b(class|record|interface|enum)\\s+(\\w+)");
    private static final Pattern IMPORT = Pattern.compile("import\\s+com\\.agentic\\.shortener\\.[\\w.]*\\.(\\w+);");
    private static final Pattern CLASS_MAPPING = Pattern.compile("@RequestMapping\\(\"([^\"]*)\"\\)");
    /** A verb mapping with or without arguments: {@code @PostMapping}, {@code @GetMapping("/x")}, {@code @DeleteMapping(value = "/x")}. */
    private static final Pattern METHOD_MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch)Mapping(?:\\(([^)]*)\\))?");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern METHOD_NAME = Pattern.compile("\\s(\\w+)\\s*\\(");
    private static final Pattern TABLE = Pattern.compile("@Table\\(name\\s*=\\s*\"(\\w+)\"");

    public record ClassInfo(String name, String path, String kind, List<String> dependsOn) {
    }

    public record Endpoint(String verb, String path, String handler) {
    }

    public record Index(List<ClassInfo> classes, List<Endpoint> endpoints, List<String> tables,
                        List<String> migrations) {
    }

    private CodeIndex() {
    }

    public static Index build(WorkspaceFs fs) {
        List<ClassInfo> classes = new ArrayList<>();
        List<Endpoint> endpoints = new ArrayList<>();
        Set<String> tables = new LinkedHashSet<>();
        for (String path : fs.listFiles(SOURCE_DIR)) {
            if (!path.endsWith(".java")) {
                continue;
            }
            String source = fs.read(path);
            Matcher type = TYPE.matcher(source);
            if (type.find()) {
                List<String> deps = new ArrayList<>();
                Matcher imports = IMPORT.matcher(source);
                while (imports.find()) {
                    deps.add(imports.group(1));
                }
                classes.add(new ClassInfo(type.group(2), path, type.group(1), deps));
            }
            Matcher base = CLASS_MAPPING.matcher(source);
            String prefix = base.find() ? base.group(1) : "";
            Matcher mapping = METHOD_MAPPING.matcher(source);
            while (mapping.find()) {
                Matcher name = METHOD_NAME.matcher(source.substring(mapping.end()));
                String handler = name.find() ? name.group(1) : "?";
                Matcher quoted = QUOTED.matcher(mapping.group(2) == null ? "" : mapping.group(2));
                String route = quoted.find() ? quoted.group(1) : "";
                endpoints.add(new Endpoint(mapping.group(1).toUpperCase(Locale.ROOT), prefix + route, handler));
            }
            Matcher table = TABLE.matcher(source);
            while (table.find()) {
                tables.add(table.group(1));
            }
        }
        List<String> migrations = fs.listFiles("src/main/resources/db/migration").stream()
                .map(p -> p.substring(p.lastIndexOf('/') + 1)).toList();
        return new Index(classes, endpoints, List.copyOf(tables), migrations);
    }

    /** Classes whose source mentions any keyword, best match first. */
    public static List<ClassInfo> relevant(WorkspaceFs fs, Index index, List<String> keywords) {
        record Scored(ClassInfo info, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (ClassInfo info : index.classes()) {
            String source = fs.read(info.path()).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String keyword : keywords) {
                String k = keyword.toLowerCase(Locale.ROOT);
                int from = 0;
                while (!k.isBlank() && (from = source.indexOf(k, from)) >= 0) {
                    score++;
                    from += k.length();
                }
            }
            if (score > 0) {
                scored.add(new Scored(info, score));
            }
        }
        return scored.stream().sorted(Comparator.comparingInt(Scored::score).reversed())
                .map(Scored::info).toList();
    }
}
