package org.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the settings the settings dialog exposes, directly in {@code application.yaml}.
 * <p>
 * Saving edits the file line by line instead of re-serializing it with a YAML library, so that the comments the
 * file is full of are preserved. This only works because the file has a small, fixed structure that is fully known
 * here; a general-purpose YAML editor would need a comment-preserving YAML library, which this project does not
 * otherwise depend on.
 */
final class AppSettingsFile {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppSettingsFile.class);

    /**
     * Matches a YAML mapping entry's indentation, key and everything after the colon (its value, its trailing
     * comment, or both). Does not match comment or blank lines.
     */
    private static final Pattern KEY_LINE = Pattern.compile("^(?<indent>\\s*)(?<key>[\\w.\\-]+):(?<rest>.*)$");

    private AppSettingsFile() {
    }

    static Path locate() {
        return Path.of(System.getProperty("user.dir"), "application.yaml");
    }

    @SuppressWarnings("unchecked")
    static AppSettings load(Path path) {
        Map<String, Object> root = Map.of();

        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);

            if (loaded instanceof Map) {
                root = (Map<String, Object>) loaded;
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read {}. Showing default settings.", path, e);
        }

        String theme = asString(navigate(root, "ui", "theme"), "dark");

        return new AppSettings(
                asInt(navigate(root, "server", "port"), 8080),
                asString(navigate(root, "ontology", "file"), ""),
                asString(navigate(root, "reasoner", "konclude", "owllinkserver"), "http://localhost:8080"),
                asString(navigate(root, "reasoner", "konclude", "folder"), "${user.home}/Downloads"),
                asString(navigate(root, "reasoner", "konclude", "workers"), "AUTO"),
                asBoolean(navigate(root, "reasoner", "konclude", "keep-running"), false),
                asString(navigate(root, "spring", "security", "user", "name"), "user"),
                asString(navigate(root, "spring", "security", "user", "password"), ""),
                asString(navigate(root, "logging", "level", "root"), "info"),
                asString(navigate(root, "logging", "level", "org.springframework"), "warn"),
                asString(navigate(root, "logging", "level", "o.s.s.web"), "warn"),
                asString(navigate(root, "logging", "level", "org.apache"), "warn"),
                asString(navigate(root, "logging", "file", "path"), "log"),
                "light".equalsIgnoreCase(theme) ? AppSettings.Theme.LIGHT : AppSettings.Theme.DARK
        );
    }

    static void save(Path path, AppSettings settings) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));

        Map<String, String> newValueByPath = new LinkedHashMap<>();
        newValueByPath.put("server.port", plain(Integer.toString(settings.serverPort())));
        newValueByPath.put("ontology.file", quotedPath(settings.ontologyFile()));
        newValueByPath.put("reasoner.konclude.owllinkserver", plain(settings.koncludeOwlLinkServer()));
        newValueByPath.put("reasoner.konclude.folder", quotedPath(settings.koncludeFolder()));
        newValueByPath.put("reasoner.konclude.workers", plain(settings.koncludeWorkers()));
        newValueByPath.put("reasoner.konclude.keep-running", plain(Boolean.toString(settings.koncludeKeepRunning())));
        newValueByPath.put("spring.security.user.name", quoted(settings.securityUsername()));
        newValueByPath.put("spring.security.user.password", quoted(settings.securityPassword()));
        newValueByPath.put("logging.level.root", plain(settings.logLevelRoot()));
        newValueByPath.put("logging.level.org.springframework", plain(settings.logLevelSpringFramework()));
        newValueByPath.put("logging.level.o.s.s.web", plain(settings.logLevelSpringSecurityWeb()));
        newValueByPath.put("logging.level.org.apache", plain(settings.logLevelApache()));
        newValueByPath.put("logging.file.path", quotedPath(settings.logFilePath()));
        newValueByPath.put("ui.theme", plain(settings.theme() == AppSettings.Theme.LIGHT ? "light" : "dark"));

        Set<String> remaining = new LinkedHashSet<>(newValueByPath.keySet());
        Deque<String> pathStack = new ArrayDeque<>();

        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = KEY_LINE.matcher(lines.get(i));

            if (!matcher.matches()) {
                continue;
            }

            String indent = matcher.group("indent");
            String key = matcher.group("key");
            String rest = matcher.group("rest");
            int depth = indent.length() / 2;

            while (pathStack.size() > depth) {
                pathStack.removeLast();
            }

            String strippedRest = rest.strip();

            if (strippedRest.isEmpty() || strippedRest.startsWith("#")) {
                // a section header: its settings are nested in the following, more indented lines
                pathStack.addLast(key);
                continue;
            }

            String fullPath = pathStack.isEmpty() ? key : String.join(".", pathStack) + "." + key;
            String newValue = newValueByPath.get(fullPath);

            if (newValue != null) {
                lines.set(i, indent + key + ": " + newValue + trailingComment(rest));
                remaining.remove(fullPath);
            }
        }

        if (remaining.remove("ui.theme")) {
            lines.add("");
            lines.add("ui:");
            lines.add("  theme: " + newValueByPath.get("ui.theme"));
        }

        if (!remaining.isEmpty()) {
            LOGGER.warn("Could not find these settings in {} to update; they were left unchanged: {}", path, remaining);
        }

        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    /**
     * @return The comment at the end of a mapping entry's value, including a separating space, or an empty string
     * if there is none. Quoted values are never searched for one: a "#" there could be part of the value rather
     * than a real comment, and none of the values this class quotes currently have one anyway.
     */
    private static String trailingComment(String rest) {
        String withoutLeadingSpace = rest.stripLeading();

        if (withoutLeadingSpace.startsWith("\"") || withoutLeadingSpace.startsWith("'")) {
            return "";
        }

        Matcher matcher = Pattern.compile("(?:^|\\s)(#.*)$").matcher(rest);
        return matcher.find() ? " " + matcher.group(1) : "";
    }

    private static String plain(String value) {
        return value;
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Normalizes path separators to forward slashes before quoting, matching this file's own convention and
     * avoiding backslashes being misread as escape sequences in the quoted YAML string.
     */
    private static String quotedPath(String path) {
        return quoted(path.replace('\\', '/'));
    }

    private static Object navigate(Map<String, Object> root, String... keys) {
        Object current = root;

        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }

            current = map.get(key);
        }

        return current;
    }

    private static String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int asInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String string) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        return defaultValue;
    }

    private static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }

        if (value instanceof String string) {
            return Boolean.parseBoolean(string.trim());
        }

        return defaultValue;
    }
}
