package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Petit lexique externe, non lié à une phrase précise. CoreNLP ne fournit pas de dictionnaire
 * de synonymes ni de registre lexical; ce fournisseur est donc volontairement COURANT.
 */
public final class ResourceSynonymProvider implements SynonymProvider {
    private final Map<String, String> synonyms;

    public ResourceSynonymProvider() {
        this("/synonyms-fr.tsv");
    }

    ResourceSynonymProvider(String resource) {
        this.synonyms = load(resource);
    }

    @Override
    public Optional<String> synonym(TokenData token, SynonymRegister register) {
        String key = token.word().toLowerCase(Locale.ROOT);
        String value = synonyms.get(key);
        if (value == null || value.isBlank() || value.equalsIgnoreCase(token.word())) return Optional.empty();
        return Optional.of(matchCase(token.word(), value));
    }

    @Override
    public boolean isCuratedCandidate(TokenData token, SynonymRegister register, String candidate) {
        return synonym(token, register).map(v -> v.equalsIgnoreCase(candidate)).orElse(false);
    }

    @Override
    public boolean supportsRegisters() {
        return false;
    }

    private static Map<String, String> load(String resource) {
        InputStream in = ResourceSynonymProvider.class.getResourceAsStream(resource);
        if (in == null) throw new IllegalStateException("Lexique introuvable: " + resource);
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\t", 2);
                if (parts.length != 2) throw new IllegalStateException("Ligne de lexique invalide: " + line);
                map.put(parts[0].toLowerCase(Locale.ROOT), parts[1]);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Lecture du lexique impossible", e);
        }
        return Map.copyOf(map);
    }

    private static String matchCase(String original, String replacement) {
        if (original.isEmpty()) return replacement;
        if (original.equals(original.toUpperCase(Locale.ROOT))) return replacement.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(original.codePointAt(0))) {
            return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
        }
        return replacement;
    }
}
