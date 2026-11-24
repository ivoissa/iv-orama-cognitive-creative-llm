package com.ivorama.nlp.transform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Lecteur volontairement strict du YAML plat utilisé par les règles du projet. */
public final class TransformationConfigLoader {
    private TransformationConfigLoader() {}

    public record GlobalConfig(TransformationConfig transformations, LexicalConfig lexical,
                               DecisionWeights weights, boolean benchmarkEnabled, String defaultSentence) {
        public GlobalConfig(TransformationConfig transformations, LexicalConfig lexical, DecisionWeights weights) {
            this(transformations, lexical, weights, true, null);
        }
    }
    public static Path defaultPath() {
        Path local = Path.of("config/application.yml");
        Path parent = Path.of("../config/application.yml");
        return !Files.isRegularFile(local) && Files.isRegularFile(parent) ? parent : local;
    }

    public static Map<String, String> readSettings(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return Map.copyOf(readValues(in));
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de lire " + path, e);
        }
    }

    public static GlobalConfig loadGlobal(Path path) {
        return loadGlobal(path, true);
    }

    public static GlobalConfig loadGlobal(Path path, boolean benchmarkDefault) {
        try (InputStream in = Files.newInputStream(path)) {
            var values = readValues(in);
            // Validate all three views before creating any lexical provider.
            return new GlobalConfig(toConfig(values), toLexicalConfig(values), decisionWeights(values),
                    boolValue(values, "benchmark_enabled", benchmarkDefault), values.get("default_sentence"));
        } catch (IOException e) { throw new IllegalStateException("Impossible de lire " + path, e); }
    }

    public static TransformationConfig loadDefault() {
        InputStream in = TransformationConfigLoader.class.getResourceAsStream("/transformation-rules.yml");
        if (in == null) throw new IllegalStateException("transformation-rules.yml absent du classpath");
        return parse(in);
    }

    public static TransformationConfig load(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de lire " + path, e);
        }
    }

    static TransformationConfig parse(InputStream in) {
        Map<String, String> values = readValues(in);
        return toConfig(values);
    }

    /** YAML à sections imbriquées, deux espaces par niveau ; aucun tableau ou alias YAML. */
    private static Map<String, String> readValues(InputStream in) {
        Map<String, String> values = new HashMap<>();
        java.util.List<String> parents = new java.util.ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            var lines = reader.lines().toList();
            for (int index = 0; index < lines.size(); index++) {
                int lineNo = index + 1;
                String withoutComments = stripComment(lines.get(index));
                if (withoutComments.isBlank() || withoutComments.trim().equals("---")) continue;
                int indent = 0;
                while (indent < withoutComments.length() && withoutComments.charAt(indent) == ' ') indent++;
                if (indent % 2 != 0 || indent / 2 > parents.size())
                    throw new IllegalArgumentException("Indentation YAML invalide ligne " + lineNo);
                String trimmed = withoutComments.trim();
                int colon = trimmed.indexOf(':');
                if (colon <= 0) throw new IllegalArgumentException("YAML invalide ligne " + lineNo);
                String key = trimmed.substring(0, colon).trim();
                if (!key.matches("[a-z][a-z0-9_]*"))
                    throw new IllegalArgumentException("Clé YAML invalide ligne " + lineNo + ": " + key);
                String value = trimmed.substring(colon + 1).trim();
                int level = indent / 2;
                while (parents.size() > level) parents.remove(parents.size() - 1);
                String path = String.join(".", parents);
                String fullKey = path.isEmpty() ? key : path + "." + key;
                if (value.isEmpty()) {
                    if (!SECTIONS.contains(fullKey))
                        throw new IllegalArgumentException("Section YAML inconnue : " + fullKey);
                    parents.add(key);
                } else {
                    if (!ALLOWED.contains(fullKey))
                        throw new IllegalArgumentException("Clé YAML inconnue : " + fullKey);
                    if (fullKey.equals("default_sentence") && (value.equals("|") || value.equals("|-"))) {
                        int first = index + 1;
                        int end = first;
                        int blockIndent = 0;
                        while (end < lines.size()) {
                            String content = lines.get(end);
                            if (!content.isBlank()) {
                                int spaces = content.length() - content.stripLeading().length();
                                if (spaces <= indent) break;
                                if (blockIndent == 0) blockIndent = spaces;
                                if (spaces < blockIndent) {
                                    throw new IllegalArgumentException("Indentation du texte YAML invalide ligne " + (end + 1));
                                }
                            }
                            end++;
                        }
                        StringBuilder block = new StringBuilder();
                        for (int i = first; i < end; i++) {
                            String content = lines.get(i);
                            if (!content.isBlank()) block.append(content.substring(blockIndent));
                            block.append('\n');
                        }
                        int last = block.length();
                        while (last > 0 && block.charAt(last - 1) == '\n') last--;
                        value = block.substring(0, last) + (value.equals("|") && last > 0 ? "\n" : "");
                        index = end - 1;
                    } else {
                        value = unquote(value);
                    }
                    if (values.putIfAbsent(fullKey, value) != null)
                        throw new IllegalArgumentException("Clé YAML dupliquée : " + fullKey);
                }
            }
        } catch (IOException e) { throw new IllegalStateException("Lecture YAML impossible", e); }
        return values;
    }

    private static final java.util.Set<String> SECTIONS = java.util.Set.of(
        "llm", "security", "tools", "rendering", "transformations", "transformations.synonym_register", "synonyms", "synonyms.wolf",
        "synonyms.jwi", "synonyms.cache", "synonyms.ranking", "synonyms.robert", "synonyms.disco");
    private static final java.util.Set<String> ALLOWED = java.util.Set.of(
        "benchmark_enabled", "default_sentence", "rendering.enabled",
        "llm.provider", "llm.api_key_env", "llm.endpoint", "llm.model", "llm.timeout_seconds", "llm.context_tokens", "llm.output_tokens",
        "llm.temperature", "llm.threads", "llm.memory_messages", "llm.gpu_layers",
        "security.mode", "security.max_input_chars", "security.max_output_chars", "security.protected_values_file",
        "tools.wikipedia_enabled",
        "synonyms.disco.semantic_freedom", "synonyms.disco.enabled", "synonyms.disco.directory", "synonyms.disco.min_group_score", "synonyms.disco.min_margin", "synonyms.disco.min_retention",
        "synonyms.robert.enabled", "synonyms.robert.context_weight", "synonyms.robert.neighborhood_weight", "synonyms.robert.min_context", "synonyms.robert.min_margin", "synonyms.robert.fallback_to_omw", "synonyms.robert.require_reviewed_source",
        "transformations.adjective_semantic_freedom", "transformations.noun_semantic_freedom", "transformations.emphase_mode", "transformations.noun_emphasis", "transformations.emphasis_fallback_limit", "transformations.direct_dictionary", "transformations.yoda_mode_enabled", "transformations.move_circumstantial_complements_enabled",
        "transformations.add_commas_around_complements_enabled",
        "transformations.replace_adjectives_with_synonyms_enabled",
        "transformations.replace_first_and_third_nouns_enabled",
        "transformations.stoic_mode_enabled", "transformations.random_seed",
        "transformations.synonym_register.familiar_enabled",
        "transformations.synonym_register.common_enabled",
        "transformations.synonym_register.formal_enabled",
        "synonyms.candidate_rejections_enabled", "synonyms.enabled", "synonyms.wolf.enabled", "synonyms.jwi.enabled",
        "synonyms.jwi.dictionary_directory", "synonyms.fallback_to_wolf_when_jwi_has_no_result",
        "synonyms.cache.read_enabled", "synonyms.cache.write_enabled", "synonyms.cache.file_path",
        "synonyms.ranking.semantic_weight", "synonyms.ranking.agreement_weight");

    private static TransformationConfig toConfig(Map<String, String> values) {
        validateFreedom(values);
        boolean synonymsEnabled = boolValue(values, "synonyms.enabled", true);
        boolean nouns = boolValue(values, "transformations.replace_first_and_third_nouns_enabled", true);
        boolean adjectives = boolValue(values, "transformations.replace_adjectives_with_synonyms_enabled", false);
        // Le moteur historique remplace les noms dès qu'un registre est activé ;
        // le drapeau explicite nouns évite toute substitution lorsque désactivé.
        boolean familiar = boolValue(values, "transformations.synonym_register.familiar_enabled", false);
        boolean common = boolValue(values, "transformations.synonym_register.common_enabled", true);
        boolean formal = boolValue(values, "transformations.synonym_register.formal_enabled", false);
        return new TransformationConfig(familiar, common, formal,
            boolValue(values, "transformations.yoda_mode_enabled", false),
            synonymsEnabled && adjectives,
            boolValue(values, "transformations.move_circumstantial_complements_enabled", false),
            boolValue(values, "transformations.add_commas_around_complements_enabled", true),
            boolValue(values, "transformations.stoic_mode_enabled", false),
            longValue(values, "transformations.random_seed", 20261107L), synonymsEnabled && nouns, boolValue(values,"transformations.emphase_mode",false),
            parseNounEmphasis(values.getOrDefault("transformations.noun_emphasis", "")),
            boolValue(values, "transformations.direct_dictionary", false),
            Math.toIntExact(longValue(values, "transformations.emphasis_fallback_limit", 1)),
            liteSubstitutionRate(values, "transformations.adjective_semantic_freedom"),
            liteSubstitutionRate(values, "transformations.noun_semantic_freedom"),
            boolValue(values, "synonyms.candidate_rejections_enabled", true));
    }

    private static java.util.List<NounEmphasisRule.Adjective> parseNounEmphasis(String source) {
        if (source.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(source.split(",", -1)).map(String::strip).map(entry -> {
            var parts = entry.split(":", -1);
            if (parts.length != 2) throw new IllegalArgumentException("noun_emphasis attend lemme:avant ou lemme:apres");
            var position = switch (parts[1].strip()) {
                case "avant" -> NounEmphasisRule.Position.BEFORE_NOUN;
                case "apres" -> NounEmphasisRule.Position.AFTER_NOUN;
                default -> throw new IllegalArgumentException("Position noun_emphasis invalide : " + parts[1]);
            };
            return new NounEmphasisRule.Adjective(parts[0].strip(), position);
        }).toList();
    }

    public static DecisionWeights loadDecisionWeightsDefault() {
        try (InputStream in=TransformationConfigLoader.class.getResourceAsStream("/transformation-rules.yml")) {
            if(in==null) throw new IllegalStateException("YAML absent");
            return decisionWeights(readValues(in));
        } catch(IOException e) { throw new IllegalStateException(e); }
    }
    public static DecisionWeights loadDecisionWeights(Path path) {
        try(InputStream in=Files.newInputStream(path)) { return decisionWeights(readValues(in)); }
        catch(IOException e) {throw new IllegalStateException(e);}
    }
    private static DecisionWeights decisionWeights(Map<String,String> v) {
        return new DecisionWeights(Double.parseDouble(v.getOrDefault("synonyms.ranking.semantic_weight","0.8")),
            Double.parseDouble(v.getOrDefault("synonyms.ranking.agreement_weight","0.2")));
    }

    public static LexicalConfig loadLexicalDefault() {
        try (InputStream in = TransformationConfigLoader.class.getResourceAsStream("/transformation-rules.yml")) {
            if (in == null) throw new IllegalStateException("transformation-rules.yml absent");
            return toLexicalConfig(readValues(in));
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    public static LexicalConfig loadLexical(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return toLexicalConfig(readValues(in));
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static LexicalConfig toLexicalConfig(Map<String, String> values) {
        validateFreedom(values);
        boolean enabled = boolValue(values, "synonyms.enabled", true);
        boolean wolf = boolValue(values, "synonyms.wolf.enabled", true);
        boolean jwi = enabled && boolValue(values, "synonyms.jwi.enabled", false);
        // OMW français embarqué reste disponible même lorsque WOLF XML et JWI sont désactivés.
        if (jwi && !wolf && boolValue(values, "synonyms.fallback_to_wolf_when_jwi_has_no_result", true))
            throw new IllegalArgumentException("Repli WOLF demandé mais WOLF désactivé");
        return new LexicalConfig(enabled, wolf, jwi,
            Path.of(values.getOrDefault("synonyms.jwi.dictionary_directory", "data/wordnet-fr/dict")),
            enabled && boolValue(values, "synonyms.cache.read_enabled", false),
            enabled && boolValue(values, "synonyms.cache.write_enabled", false),
            Path.of(values.getOrDefault("synonyms.cache.file_path", "data/synonyms-cache.tsv")),
            enabled && jwi && wolf && boolValue(values, "synonyms.fallback_to_wolf_when_jwi_has_no_result", true),
            Path.of("data/wolf.xml"),new RobertSettings(boolValue(values,"synonyms.robert.enabled",true),
                Double.parseDouble(values.getOrDefault("synonyms.robert.context_weight","1")),Double.parseDouble(values.getOrDefault("synonyms.robert.neighborhood_weight","1")),
                Double.parseDouble(values.getOrDefault("synonyms.robert.min_context","0.01")),Double.parseDouble(values.getOrDefault("synonyms.robert.min_margin","0.01")),boolValue(values,"synonyms.robert.fallback_to_omw",false),boolValue(values,"synonyms.robert.require_reviewed_source",true)),
            new DiscoSettings(boolValue(values,"synonyms.disco.enabled",false),Path.of(values.getOrDefault("synonyms.disco.directory","data/disco/fr-general-20151126-lm-sim")),Double.parseDouble(values.getOrDefault("synonyms.disco.min_group_score","0.01")),Double.parseDouble(values.getOrDefault("synonyms.disco.min_margin","0.02")),Double.parseDouble(values.getOrDefault("synonyms.disco.min_retention","0.8")),Integer.parseInt(values.getOrDefault("synonyms.disco.semantic_freedom","0")), optionalFreedom(values,"transformations.adjective_semantic_freedom"), optionalFreedom(values,"transformations.noun_semantic_freedom")));
    }

    private static int liteSubstitutionRate(Map<String, String> values, String key) {
        Integer rate = optionalFreedom(values, key);
        return rate == null || boolValue(values, "synonyms.disco.enabled", false) ? 100 : rate;
    }

    private static Integer optionalFreedom(Map<String,String> values,String key) {
        if (!values.containsKey(key)) return null;
        int n=intValue(values,key,0);
        if(n<0||n>100) throw new IllegalArgumentException(key+" doit être compris entre 0 et 100");
        return n;
    }
    private static void validateFreedom(Map<String,String> values) {
        Integer a=optionalFreedom(values,"transformations.adjective_semantic_freedom");
        Integer n=optionalFreedom(values,"transformations.noun_semantic_freedom");
    }
    private static String stripComment(String line) {
        boolean single = false, dbl = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !dbl) single = !single;
            else if (c == '"' && !single) dbl = !dbl;
            else if (c == '#' && !single && !dbl) return line.substring(0, i);
        }
        return line;
    }
    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'")))) return value.substring(1, value.length() - 1);
        return value;
    }

    private static int intValue(Map<String, String> map, String key, int def) {
        String v = map.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " doit être un entier: " + v, e); }
    }

    private static long longValue(Map<String, String> map, String key, long def) {
        String v = map.get(key);
        if (v == null) return def;
        try { return Long.parseLong(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " doit être un entier long: " + v, e); }
    }

    private static boolean boolValue(Map<String, String> map, String key, boolean def) {
        String v = map.get(key);
        if (v == null) return def;
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "oui", "1" -> true;
            case "false", "no", "non", "0" -> false;
            default -> throw new IllegalArgumentException(key + " doit être booléen: " + v);
        };
    }
}
