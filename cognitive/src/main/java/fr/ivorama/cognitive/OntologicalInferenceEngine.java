package fr.ivorama.cognitive;

import java.util.*;

/** Calcul fini sous monde ouvert, sans explosion logique en contexte contradictoire. */
public final class OntologicalInferenceEngine {
    public enum Verdict { SUPPORTED, REFUTED, UNKNOWN, CONTRADICTORY }
    public record Evaluation(Verdict verdict, Optional<OntologicalProofTrace> support,
                             Optional<OntologicalProofTrace> refutation, long graphVersion,
                             boolean allSourcesHumanChecked) {
        public Evaluation {
            Objects.requireNonNull(verdict);
            Objects.requireNonNull(support);
            Objects.requireNonNull(refutation);
        }
    }
    private record Path(String at, List<RelationAssertion> steps) {}

    public Evaluation evaluate(OntologicalKnowledgeGraph graph, String subject, RelationAssertion.Predicate predicate,
                               String target) {
        Objects.requireNonNull(graph);
        ConceptNode a = graph.node(subject), b = graph.node(target);
        if (predicate == RelationAssertion.Predicate.INSTANCE_OF &&
                (a.kind() != ConceptNode.Kind.INDIVIDUAL || b.kind() != ConceptNode.Kind.CLASS))
            throw new IllegalArgumentException("Types non compatibles pour INSTANCE_OF");
        if (predicate == RelationAssertion.Predicate.SUBCLASS_OF &&
                (a.kind() != ConceptNode.Kind.CLASS || b.kind() != ConceptNode.Kind.CLASS))
            throw new IllegalArgumentException("Types non compatibles pour SUBCLASS_OF");
        List<RelationAssertion> current = graph.currentAssertions();
        OntologicalProofTrace positive = findPositive(graph, subject, predicate, target, current);
        OntologicalProofTrace negative = null;
        for (RelationAssertion r : current) {
            if (!r.positive() && r.subjectId().equals(subject) && r.predicate()==predicate
                    && r.objectId().equals(target)) {
                negative = new OntologicalProofTrace(subject, predicate, target, false, List.of(r));
                break;
            }
        }
        if (positive != null && !positive.verify(graph))
            throw new IllegalStateException("Échec du contrôle du certificat positif");
        if (negative != null && !negative.verify(graph))
            throw new IllegalStateException("Échec du contrôle du certificat négatif");
        Verdict status = positive != null && negative != null ? Verdict.CONTRADICTORY
                : positive != null ? Verdict.SUPPORTED : negative != null ? Verdict.REFUTED : Verdict.UNKNOWN;
        boolean verified = (positive != null || negative != null);
        for (OntologicalProofTrace proof : Arrays.asList(positive, negative)) {
            if (proof == null) continue;
            for (String source : proof.sourceIds())
                verified &= graph.sources().required(source).status() == KnowledgeSourceRegistry.Status.HUMAN_CHECKED;
        }
        return new Evaluation(status, Optional.ofNullable(positive), Optional.ofNullable(negative),
                graph.version(), verified);
    }

    private OntologicalProofTrace findPositive(OntologicalKnowledgeGraph graph, String subject,
            RelationAssertion.Predicate predicate, String target, List<RelationAssertion> current) {
        if (predicate == RelationAssertion.Predicate.SUBCLASS_OF && subject.equals(target))
            return new OntologicalProofTrace(subject, predicate, target, true, List.of());
        Map<String,List<RelationAssertion>> outgoing = new HashMap<>();
        for (RelationAssertion r : current)
            if (r.positive()) outgoing.computeIfAbsent(r.subjectId(), k -> new ArrayList<>()).add(r);
        ArrayDeque<Path> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(new Path(subject, List.of())); seen.add(subject);
        while (!q.isEmpty()) {
            Path path = q.removeFirst();
            for (RelationAssertion r : outgoing.getOrDefault(path.at(), List.of())) {
                if (predicate == RelationAssertion.Predicate.SUBCLASS_OF
                        && r.predicate() != RelationAssertion.Predicate.SUBCLASS_OF) continue;
                if (predicate == RelationAssertion.Predicate.INSTANCE_OF &&
                        r.predicate() != (path.steps().isEmpty() ? RelationAssertion.Predicate.INSTANCE_OF
                                : RelationAssertion.Predicate.SUBCLASS_OF)) continue;
                List<RelationAssertion> next = new ArrayList<>(path.steps()); next.add(r);
                if (r.objectId().equals(target))
                    return new OntologicalProofTrace(subject, predicate, target, true, next);
                if (seen.add(r.objectId())) q.addLast(new Path(r.objectId(), List.copyOf(next)));
            }
        }
        return null;
    }
}
