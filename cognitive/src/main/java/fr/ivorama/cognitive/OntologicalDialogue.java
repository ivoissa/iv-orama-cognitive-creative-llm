package fr.ivorama.cognitive;

import java.util.*;

/** Adaptateur de question française vers la couche ontologique ; sans modifier FCI/RDO. */
public final class OntologicalDialogue {
    private final OntologicalKnowledgeGraph graph;
    private final FrenchOntologicalQuestionParser parser = new FrenchOntologicalQuestionParser();
    private final OntologicalInferenceEngine engine = new OntologicalInferenceEngine();
    public OntologicalDialogue(OntologicalKnowledgeGraph graph) { this.graph = Objects.requireNonNull(graph); }

    public String ask(String question) {
        FrenchOntologicalQuestionParser.ParseResult p = parser.parse(question, graph);
        if (p.status() != FrenchOntologicalQuestionParser.Status.PARSED)
            return "["+p.status()+"] " + p.explanation();
        OntologicalInferenceEngine.Evaluation e = engine.evaluate(graph, p.subject(), p.predicate(), p.target());
        String verdict = switch (e.verdict()) {
            case SUPPORTED -> "OUI, selon les prémisses disponibles";
            case REFUTED -> "NON, selon une assertion négative explicite";
            case UNKNOWN -> "INDÉTERMINÉ : aucune preuve ni réfutation enregistrée";
            case CONTRADICTORY -> "CONTRADICTOIRE : soutien et réfutation enregistrés";
        };
        StringBuilder report = new StringBuilder(verdict)
                .append("\nInterprétation : ").append(p.explanation())
                .append("\nVersion du graphe : ").append(e.graphVersion());
        e.support().ifPresent(proof -> report.append("\nPreuve positive : ").append(proof.assertionIds())
                .append(" ; sources : ").append(proof.sourceIds()));
        e.refutation().ifPresent(proof -> report.append("\nPreuve négative : ").append(proof.assertionIds())
                .append(" ; sources : ").append(proof.sourceIds()));
        if (e.verdict() != OntologicalInferenceEngine.Verdict.UNKNOWN) {
            report.append(e.allSourcesHumanChecked()
                    ? "\nProvenance : sources du certificat déclarées contrôlées par un humain ; aucune vérification indépendante automatisée."
                    : "\nProvenance : au moins une source non vérifiée ; conclusion conditionnelle uniquement.");
        }
        return report.toString();
    }
}
