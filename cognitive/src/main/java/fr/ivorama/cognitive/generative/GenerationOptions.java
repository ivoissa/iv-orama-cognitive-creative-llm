package fr.ivorama.cognitive.generative;

import java.util.Properties;

/** Immutable provider options. Null keeps Ollama/model defaults on the native path. */
public record GenerationOptions(Integer seed, Boolean thinking) {
    public static GenerationOptions fromSystemProperties(boolean nativeDefaults) {
        Properties snapshot = new Properties();
        snapshot.putAll(System.getProperties());
        return from(snapshot, nativeDefaults);
    }
    static GenerationOptions from(Properties properties, boolean nativeDefaults) {
        String rawSeed = properties.getProperty("ivorama.llm.seed");
        Integer seed = nativeDefaults ? null : Integer.valueOf(42);
        if (rawSeed != null) {
            try { seed = Integer.valueOf(rawSeed); }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("ivorama.llm.seed doit être un entier signé sur32 bits");
            }
        }
        String rawThinking = properties.getProperty("ivorama.llm.think");
        Boolean thinking = nativeDefaults ? null : Boolean.FALSE;
        if (rawThinking != null) {
            if (!rawThinking.equalsIgnoreCase("true") && !rawThinking.equalsIgnoreCase("false"))
                throw new IllegalArgumentException("ivorama.llm.think attend true ou false");
            thinking = Boolean.valueOf(rawThinking);
        }
        return new GenerationOptions(seed, thinking);
    }
}
