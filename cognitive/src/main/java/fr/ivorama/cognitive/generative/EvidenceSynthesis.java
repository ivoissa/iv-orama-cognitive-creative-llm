package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import fr.ivorama.cognitive.agent.ObserverSession;
import java.util.*;
import java.util.regex.Pattern;

/** Validates source identity and exact excerpts, NOT semantic entailment or formal truth. */
public final class EvidenceSynthesis {
    private EvidenceSynthesis() { }
    private static final Pattern CITATION_MARKER = Pattern.compile("\\[F\\d+]");
    private static final System.Logger LOG = System.getLogger(EvidenceSynthesis.class.getName());
    public enum RejectionReason {
        RESPONSE_LIMIT, MALFORMED_JSON, INVALID_ROOT, CLAIMS_LIMIT, CLAIM_SHAPE,
        CLAIM_TEXT, CLAIM_KIND, SOURCES_REQUIRED, CITATION_IN_TEXT, SOURCE_SHAPE,
        UNKNOWN_SOURCE_ID, QUOTE_LIMIT, REDACTED_QUOTE, QUOTE_NOT_FOUND
    }
    /** Safe structured diagnostics: never retains model text, quotes, paths or parser causes. */
    public static final class Rejected extends IllegalArgumentException {
        private final RejectionReason reason;
        private final int claimIndex, sourceIndex;
        private Rejected(RejectionReason reason, int claimIndex, int sourceIndex) {
            super("AGENT_EVIDENCE_REJECTED: schéma, citation ou extrait non vérifiable");
            this.reason = reason; this.claimIndex = claimIndex; this.sourceIndex = sourceIndex;
        }
        public RejectionReason reason() { return reason; }
        public int claimIndex() { return claimIndex; }
        public int sourceIndex() { return sourceIndex; }
    }
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public static final String INSTRUCTION = """
            Réponds uniquement en JSON : {"claims":[{"kind":"OBSERVED","text":"constat prudent",
            "sources":[{"id":"F1","quote":"extrait exact du contenu de F1"}]}]}.
            Au maximum 6 claims. kind = OBSERVED, INFERRED, HYPOTHESIS ou UNKNOWN.
            OBSERVED décrit uniquement le code ou texte effectivement visible ; INFERRED est une déduction non certifiée.
            OBSERVED et INFERRED exigent au moins une source exacte. HYPOTHESIS et UNKNOWN peuvent avoir sources: [].
            quote fait 8 à 400 caractères, copiés exactement depuis content, pas depuis la question.
            text fait au plus 500 caractères ; chaque claim comporte exactement kind, text, sources.
            Sépare explicitement ce qui manque de ce qui est visible. Un extrait tronqué n'est pas une lecture exhaustive.
            Aucun test, compilation ou résultat d'exécution ne peut être affirmé sur la seule lecture du code.
            """;
    public static String validateAndRender(String response, List<ObserverSession.Evidence> evidence) {
        if (response == null || response.length() > 12_000)
            throw invalid(RejectionReason.RESPONSE_LIMIT, -1, -1);
        try {
            JsonNode root = JSON.readTree(response);
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("claims").isArray())
                throw invalid(RejectionReason.INVALID_ROOT, -1, -1);
            if (root.path("claims").isEmpty() || root.path("claims").size() > 6)
                throw invalid(RejectionReason.CLAIMS_LIMIT, -1, -1);
            Map<String, ObserverSession.Evidence> byId = new HashMap<>();
            evidence.forEach(e -> byId.put(e.id(), e));
            StringBuilder out = new StringBuilder("Analyse générée : extraits vérifiés, sens non certifié.\n");
            int ci = -1;
            for (JsonNode claim : root.path("claims")) {
                ci++;
                if (!claim.isObject() || claim.size() != 3 || !claim.path("kind").isTextual()
                        || !claim.path("text").isTextual() || !claim.path("sources").isArray()
                        || claim.path("sources").size() > 3)
                    throw invalid(RejectionReason.CLAIM_SHAPE, ci, -1);
                String kind = claim.path("kind").asText(), text = claim.path("text").asText();
                if (text.isBlank() || text.length() > 500) throw invalid(RejectionReason.CLAIM_TEXT, ci, -1);
                if (!Set.of("OBSERVED", "INFERRED", "HYPOTHESIS", "UNKNOWN").contains(kind))
                    throw invalid(RejectionReason.CLAIM_KIND, ci, -1);
                if (Set.of("OBSERVED", "INFERRED").contains(kind) && claim.path("sources").isEmpty())
                    throw invalid(RejectionReason.SOURCES_REQUIRED, ci, -1);
                String label = switch (kind) {
                    case "OBSERVED" -> "Observation du texte";
                    case "INFERRED" -> "Déduction non certifiée";
                    case "HYPOTHESIS" -> "Hypothèse non vérifiée";
                    default -> "Information manquante";
                };
                if (CITATION_MARKER.matcher(text).find())
                    throw invalid(RejectionReason.CITATION_IN_TEXT, ci, -1);
                out.append(label).append(" : ").append(text).append('\n');
                int si = -1;
                for (JsonNode source : claim.path("sources")) {
                    si++;
                    if (!source.isObject() || source.size() != 2 || !source.path("id").isTextual()
                            || !source.path("quote").isTextual())
                        throw invalid(RejectionReason.SOURCE_SHAPE, ci, si);
                    String id = source.path("id").asText(), quote = source.path("quote").asText();
                    var found = byId.get(id);
                    if (found == null) throw invalid(RejectionReason.UNKNOWN_SOURCE_ID, ci, si);
                    if (quote.length() < 8 || quote.length() > 400 || quote.isBlank())
                        throw invalid(RejectionReason.QUOTE_LIMIT, ci, si);
                    if (quote.contains("[REDACTED")) throw invalid(RejectionReason.REDACTED_QUOTE, ci, si);
                    if (!found.content().contains(quote)) throw invalid(RejectionReason.QUOTE_NOT_FOUND, ci, si);
                    out.append('[').append(id).append("] « ").append(quote).append(" »\n");
                }
            }
            return out.toString();
        } catch (java.io.IOException error) {
            throw invalid(RejectionReason.MALFORMED_JSON, -1, -1);
        }
    }
    private static Rejected invalid(RejectionReason reason, int claim, int source) {
        LOG.log(System.Logger.Level.DEBUG, "AGENT_EVIDENCE_REJECTED reason={0} claim={1} source={2}", reason, claim, source);
        return new Rejected(reason, claim, source);
    }
}
