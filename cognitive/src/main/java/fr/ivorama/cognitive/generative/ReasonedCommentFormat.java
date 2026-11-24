package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.*;
import fr.ivorama.cognitive.agent.ObserverSession;
import java.util.*;

/** Citation identities are generated, exact documentary text stays in the Java receipt.
 * Shape and terminal punctuation are checked, never semantic truth or grammatical completeness.
 */
final class ReasonedCommentFormat {
    private ReasonedCommentFormat() { }
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["text","evidenceIds"],"properties":{
              "text":{"type":"string","minLength":1,"maxLength":320},
              "evidenceIds":{"type":"array","maxItems":5,"uniqueItems":true,
                "items":{"type":"string","enum":["F1","F2","F3","F4","F5"]}}
            }}
            """;
    static ResponseFormat format() {
        return ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(JsonSchema.builder()
                .name("reasoned_comment").rootElement(JsonRawSchema.from(SCHEMA)).build()).build();
    }
    static String validateAndRender(String response, List<ObserverSession.Evidence> evidence) {
        try {
            JsonNode root = JSON.readTree(response);
            if(root==null || !root.isObject() || root.size()!=2 || !root.path("text").isTextual()
                    || !root.path("evidenceIds").isArray() || root.path("evidenceIds").size()>5) throw invalid();
            String text=root.path("text").asText().strip();
            // This rejects the observed unfinished suffix; punctuation is not a semantic proof.
            if(text.isBlank() || text.length()>320 || !text.matches("(?s).*[.!?…]$")
                    || text.matches("(?s).*\\[F\\d+].*")) throw invalid();
            Set<String> known=new HashSet<>(), selected=new LinkedHashSet<>();
            evidence.forEach(e->known.add(e.id()));
            for(JsonNode id:root.path("evidenceIds"))
                if(!id.isTextual() || !known.contains(id.asText()) || !selected.add(id.asText())) throw invalid();
            return "Commentaire généré, sens non certifié : " + text
                    + (selected.isEmpty()?"":"\nObservations référencées : "
                    + String.join(" ",selected.stream().map(id->"["+id+"]").toList()));
        } catch (java.io.IOException error) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("AGENT_COMMENT_REJECTED: format, terminaison ou référence invalide");
    }
}
