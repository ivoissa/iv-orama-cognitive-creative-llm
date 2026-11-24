package fr.ivorama.cognitive;

import java.text.Normalizer;
import java.util.*;

/** Graphe traçable et typé. Les rétractions demeurent enregistrées. */
public final class OntologicalKnowledgeGraph {
    public record StoredAssertion(RelationAssertion assertion, boolean active) {}
    private final Map<String, ConceptNode> nodes = new LinkedHashMap<>();
    private final Map<String, StoredAssertion> assertions = new LinkedHashMap<>();
    private final Map<String, Set<String>> lexicalIndex = new LinkedHashMap<>();
    private final KnowledgeSourceRegistry sources = new KnowledgeSourceRegistry();
    private long version;
    public synchronized long version() { return version + sources.version(); }
    public KnowledgeSourceRegistry sources() { return sources; }

    public synchronized void addNode(ConceptNode node) {
        Objects.requireNonNull(node);
        if (nodes.putIfAbsent(node.id(), node) != null)
            throw new IllegalArgumentException("Concept déjà existant: " + node.id());
        alias(node.label(), node.id());
        version++;
    }
    public synchronized void alias(String expression, String conceptId) {
        if (!nodes.containsKey(conceptId)) throw new IllegalArgumentException("Concept inconnu: " + conceptId);
        String word = normalized(expression);
        if (word.isEmpty()) throw new IllegalArgumentException("Alias vide");
        if (lexicalIndex.computeIfAbsent(word, k -> new LinkedHashSet<>()).add(conceptId)) version++;
    }
    public synchronized List<ConceptNode> candidates(String expression) {
        return lexicalIndex.getOrDefault(normalized(expression), Set.of()).stream()
                .map(nodes::get).toList();
    }
    public synchronized ConceptNode node(String id) {
        ConceptNode node = nodes.get(id);
        if (node == null) throw new IllegalArgumentException("Concept inconnu: " + id);
        return node;
    }
    public synchronized Collection<ConceptNode> nodes() { return List.copyOf(nodes.values()); }
    public synchronized Map<String, Set<String>> aliases() {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        lexicalIndex.forEach((k,v) -> copy.put(k, Set.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }
    public synchronized void addAssertion(RelationAssertion assertion) {
        Objects.requireNonNull(assertion);
        ConceptNode subject = node(assertion.subjectId());
        ConceptNode object = node(assertion.objectId());
        sources.required(assertion.sourceId());
        if (assertion.predicate() == RelationAssertion.Predicate.INSTANCE_OF &&
                (subject.kind() != ConceptNode.Kind.INDIVIDUAL || object.kind() != ConceptNode.Kind.CLASS))
            throw new IllegalArgumentException("INSTANCE_OF exige individu -> classe");
        if (assertion.predicate() == RelationAssertion.Predicate.SUBCLASS_OF &&
                (subject.kind() != ConceptNode.Kind.CLASS || object.kind() != ConceptNode.Kind.CLASS))
            throw new IllegalArgumentException("SUBCLASS_OF exige classe -> classe");
        if (assertions.putIfAbsent(assertion.id(), new StoredAssertion(assertion,true)) != null)
            throw new IllegalArgumentException("Assertion déjà existante: " + assertion.id());
        version++;
    }
    public synchronized void retract(String assertionId) {
        StoredAssertion current = assertions.get(assertionId);
        if (current == null || !current.active())
            throw new IllegalArgumentException("Assertion inconnue ou déjà retirée: " + assertionId);
        assertions.put(assertionId, new StoredAssertion(current.assertion(), false));
        version++;
    }
    public synchronized Collection<StoredAssertion> history() { return List.copyOf(assertions.values()); }
    public synchronized List<RelationAssertion> currentAssertions() {
        List<RelationAssertion> result = new ArrayList<>();
        for (StoredAssertion item : assertions.values())
            if (item.active() && sources.usable(item.assertion().sourceId())) result.add(item.assertion());
        return List.copyOf(result);
    }
    public synchronized boolean current(RelationAssertion a) {
        StoredAssertion entry = assertions.get(a.id());
        return entry != null && entry.active() && entry.assertion().equals(a) && sources.usable(a.sourceId());
    }
    /** Normalisation lexicale uniquement : jamais une interprétation sémantique. */
    public static String normalized(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").replace('’','\'').replaceAll("\\s+", " ");
    }
}
