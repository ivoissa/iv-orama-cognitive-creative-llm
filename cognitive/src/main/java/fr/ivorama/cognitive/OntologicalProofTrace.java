package fr.ivorama.cognitive;

import java.util.*;

/** Certificat de chemin : sa validité est recalculée sur le graphe courant. */
public record OntologicalProofTrace(String subjectId, RelationAssertion.Predicate predicate,
        String objectId, boolean positive, List<RelationAssertion> steps) {
    public OntologicalProofTrace { steps = List.copyOf(steps); Objects.requireNonNull(predicate); }

    /** Vérificateur structurel indépendant du parcours qui a trouvé le certificat. */
    public boolean verify(OntologicalKnowledgeGraph graph) {
        Objects.requireNonNull(graph);
        ConceptNode subject, target;
        try { subject = graph.node(subjectId); target = graph.node(objectId); }
        catch (IllegalArgumentException ex) { return false; }
        if (predicate == RelationAssertion.Predicate.INSTANCE_OF &&
                (subject.kind() != ConceptNode.Kind.INDIVIDUAL || target.kind() != ConceptNode.Kind.CLASS)) return false;
        if (predicate == RelationAssertion.Predicate.SUBCLASS_OF &&
                (subject.kind() != ConceptNode.Kind.CLASS || target.kind() != ConceptNode.Kind.CLASS)) return false;
        if (!positive) {
            if (steps.size() != 1) return false;
            RelationAssertion a = steps.get(0);
            return graph.current(a) && !a.positive() && a.subjectId().equals(subjectId)
                    && a.predicate() == predicate && a.objectId().equals(objectId);
        }
        if (steps.isEmpty()) return predicate == RelationAssertion.Predicate.SUBCLASS_OF
                && subjectId.equals(objectId); // réflexivité de l'inclusion de classes
        String cursor = subjectId;
        boolean first = true;
        for (RelationAssertion a : steps) {
            if (!graph.current(a) || !a.positive() || !a.subjectId().equals(cursor)) return false;
            if (predicate == RelationAssertion.Predicate.INSTANCE_OF) {
                if (first && a.predicate() != RelationAssertion.Predicate.INSTANCE_OF) return false;
                if (!first && a.predicate() != RelationAssertion.Predicate.SUBCLASS_OF) return false;
            } else if (a.predicate() != RelationAssertion.Predicate.SUBCLASS_OF) return false;
            cursor = a.objectId();
            first = false;
        }
        return cursor.equals(objectId);
    }
    public List<String> assertionIds() { return steps.stream().map(RelationAssertion::id).toList(); }
    public List<String> sourceIds() { return steps.stream().map(RelationAssertion::sourceId).distinct().toList(); }
}
