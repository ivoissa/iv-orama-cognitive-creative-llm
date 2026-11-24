package fr.ivorama.cognitive.generative;

import com.ivorama.nlp.transform.TransformationConfigLoader;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;

final class AssistantYaml implements AutoCloseable {
    private final Map<String, String> installed = new HashMap<>();
    private final boolean renderEnabled;

    private AssistantYaml(boolean renderEnabled) {
        this.renderEnabled = renderEnabled;
    }

    boolean renderEnabled() {
        return renderEnabled;
    }

    @Override public void close() {
        installed.forEach((property, value) -> System.getProperties().remove(property, value));
    }
    private static final Map<String, String> PROPERTIES = Map.ofEntries(
            Map.entry("llm.provider", "llm.provider"),
            Map.entry("llm.api_key_env", "llm.apiKeyEnv"),
            Map.entry("llm.endpoint", "llm.url"),
            Map.entry("llm.model", "llm.model"),
            Map.entry("llm.timeout_seconds", "llm.timeout"),
            Map.entry("llm.context_tokens", "llm.context"),
            Map.entry("llm.output_tokens", "llm.output"),
            Map.entry("llm.temperature", "llm.temperature"),
            Map.entry("llm.threads", "llm.threads"),
            Map.entry("llm.memory_messages", "llm.memory"),
            Map.entry("llm.gpu_layers", "llm.gpu"),
            Map.entry("security.mode", "security.mode"),
            Map.entry("security.max_input_chars", "security.maxInputChars"),
            Map.entry("security.max_output_chars", "security.maxOutputChars"),
            Map.entry("security.protected_values_file", "security.protectedValuesFile"),
            Map.entry("tools.wikipedia_enabled", "llm.wikipedia"));

    static AssistantYaml configure(Path path) {
        var values = TransformationConfigLoader.readSettings(path);
        String rendering = values.getOrDefault("rendering.enabled", "false");
        if (!rendering.equalsIgnoreCase("true") && !rendering.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("rendering.enabled doit valoir true ou false");
        }
        var scope = new AssistantYaml(Boolean.parseBoolean(rendering));
        PROPERTIES.forEach((key, property) -> {
            if (values.containsKey(key)) {
                String full = "ivorama." + property;
                String value = values.get(key);
                if (System.getProperties().putIfAbsent(full, value) == null) scope.installed.put(full, value);
            }
        });
        return scope;
    }
}
