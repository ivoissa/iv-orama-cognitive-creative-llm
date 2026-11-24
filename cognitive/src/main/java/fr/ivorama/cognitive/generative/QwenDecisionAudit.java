package fr.ivorama.cognitive.generative;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.data.message.*;
import fr.ivorama.cognitive.agent.*;
import java.io.IOException;
import java.util.*;

/** Opt-in single LLM proposal followed by deterministic replay, with fail-closed reporting. */
public final class QwenDecisionAudit {
    private QwenDecisionAudit() { }
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final String SYSTEM="""
            Tu proposes UN diagnostic STRUCTURÉ, sans exécuter aucune action. Les observations
            sont des témoins textuels conditionnels, jamais une causalité démontrée dans le monde.
            Retourne exclusivement l'objet JSON :
            {"kind":"CAUSE", "cause":"identifiant", "evidenceIds":["F1"], "claimsCompleteAudit":false}
            kind = CAUSE | INDETERMINATE | INCONSISTENT. Pour les deux derniers, cause="".
            Ne revendique pas un audit achevé lorsqu'il reste des sondes/pages ou des lectures
            indisponibles. evidenceIds désigne les lectures qui supportent réellement la conclusion.
            Si le modèle ne permet pas de conclure, choisis INDETERMINATE et cause="".
            Pas d'autre champ, pas de markdown, pas de shell et pas de commandes.
            """;
    public record Audit(AgentDecisionVerifier.Proposal proposal, AgentDecisionVerifier.Verdict verdict,
                        String failure) {
        public boolean accepted() { return verdict!=null && verdict.accepted(); }
    }
    public static Audit audit(ReasonedObserverSession.Plan plan,ReasonedObserverSession.Result result,
                              String question,GenerationBackend backend) {
        Objects.requireNonNull(plan);Objects.requireNonNull(result);Objects.requireNonNull(question);
        Objects.requireNonNull(backend);
        try {
            var witnesses=new ArrayList<Map<String,Object>>();
            for(var evidence:result.report().evidence()) {
                String selectedSignal=result.steps().stream().filter(s->s.evidenceId().equals(evidence.id()))
                        .map(ReasonedObserverSession.Step::signal).findFirst().orElse("");
                for(var w:ReasonedObserverSession.recognizedWitnesses(plan,evidence))
                    if(w.signal().equals(selectedSignal))witnesses.add(Map.of("evidenceId",w.evidenceId(),
                            "signal",w.signal(),"value",w.value()));
            }
            var payload=new LinkedHashMap<String,Object>();
            payload.put("question",question);
            payload.put("hypotheses",plan.hypotheses());
            payload.put("exhaustiveUniqueModelAssumed",plan.exhaustiveUnique());
            payload.put("recognizedWitnessesOnly",witnesses);
            payload.put("completion",result.completion());
            payload.put("readEvidenceIds",result.report().evidence().stream().map(ObserverSession.Evidence::id).toList());
            var resultText=GenerationBackend.measured(backend,"agent-decision-audit")
                    .generate(List.of(SystemMessage.from(SYSTEM),UserMessage.from(JSON.writeValueAsString(payload))),ignored->{});
            if(resultText.truncated() || resultText.text().length()>2048)
                return new Audit(null,null,"Proposition tronquée ou trop volumineuse");
            var proposed=parse(resultText.text());
            var decision=AgentDecisionVerifier.verify(plan,result,proposed);
            return new Audit(proposed,decision,"");
        }catch(RuntimeException | IOException failure) {
            // Don't expose untrusted model output, or rewrite/suppress the original observer report.
            return new Audit(null,null,"Contrôle non obtenu : "+failure.getClass().getSimpleName());
        }
    }
    private static AgentDecisionVerifier.Proposal parse(String value)throws IOException {
        JsonNode root=JSON.readTree(value);
        if(root==null || !root.isObject() || root.size()!=4 ||
                !root.path("kind").isTextual() || !root.path("cause").isTextual() ||
                !root.path("evidenceIds").isArray() || root.path("evidenceIds").size()>5 ||
                !root.path("claimsCompleteAudit").isBoolean()) throw new IllegalArgumentException("JSON de décision invalide");
        for(var it=root.fieldNames();it.hasNext();)if(!Set.of("kind","cause","evidenceIds","claimsCompleteAudit").contains(it.next()))
            throw new IllegalArgumentException("Champ interdit");
        var ids=new ArrayList<String>();
        for(JsonNode id:root.path("evidenceIds")) {
            if(!id.isTextual() || !id.asText().matches("F[1-5]"))
                throw new IllegalArgumentException("Référence de preuve invalide");
            ids.add(id.asText());
        }
        return new AgentDecisionVerifier.Proposal(AgentDecisionVerifier.Kind.valueOf(root.path("kind").asText()),
                root.path("cause").asText(),ids,root.path("claimsCompleteAudit").booleanValue());
    }
}
