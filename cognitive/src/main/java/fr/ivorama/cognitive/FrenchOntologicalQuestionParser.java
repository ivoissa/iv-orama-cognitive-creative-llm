package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.*;

/** Analyse déterministe de quelques formes interrogatives, sans connaissances implicites. */
public final class FrenchOntologicalQuestionParser {
    public enum Status { PARSED, AMBIGUOUS, UNKNOWN_TERM, OUT_OF_GRAMMAR }
    public record ParseResult(Status status, String subject, RelationAssertion.Predicate predicate,
                              String target, String explanation) {}
    private static final String TERM = "([a-z0-9' -]+?)";
    private static final String ARTICLE = "(?:(?:une|un|des|le|la)\\s+|l'\\s*)";
    private static final Pattern INVERTED = Pattern.compile("^"+TERM+"\\s+est-(?:il|elle)\\s+"+ARTICLE+TERM+"\\s*$");
    private static final Pattern DIRECT = Pattern.compile("^"+TERM+"\\s+est\\s+"+ARTICLE+TERM+"\\s*$");

    public record Surface(String subject, String target, boolean universal) {}
    /** Analyse de forme seulement : les mots inconnus ne deviennent pas des concepts. */
    public Optional<Surface> surface(String question) {
        if(question==null || question.isBlank())return Optional.empty();
        String text=OntologicalKnowledgeGraph.normalized(question).replaceAll("[?.!]+$", "").strip();
        text=text.replaceFirst("^est-ce que\\s+", "");
        boolean universal=text.matches("^(tout|toute|tous les|toutes les)\\s+.*");
        text=text.replaceFirst("^(tout|toute|tous les|toutes les)\\s+", "");
        Matcher m=INVERTED.matcher(text);
        if(!m.matches())m=DIRECT.matcher(text);
        if(!m.matches())return Optional.empty();
        return Optional.of(new Surface(stripArticle(m.group(1)),stripArticle(m.group(2)),universal));
    }
    public ParseResult parse(String question, OntologicalKnowledgeGraph graph) {
        var surface=surface(question);
        if(surface.isEmpty())return failure(Status.OUT_OF_GRAMMAR,
                "Forme non prise en charge (essayer : Le Soleil est-il un astre ?)");
        String left=surface.get().subject(),right=surface.get().target();
        boolean universal=surface.get().universal();
        List<ConceptNode> subjects = graph.candidates(left), targets = graph.candidates(right);
        if (subjects.size() > 1 || targets.size() > 1)
            return failure(Status.AMBIGUOUS, "Clarification : " + describe(left, subjects)
                    + " ; " + describe(right, targets));
        if (subjects.isEmpty() || targets.isEmpty())
            return failure(Status.UNKNOWN_TERM, "Concept non enregistré : "
                    + (subjects.isEmpty()?left:"") + (targets.isEmpty()?" "+right:"")
                    + ". Ajouter sa définition et une source avant de conclure.");
        ConceptNode a = subjects.get(0), b = targets.get(0);
        if (b.kind() != ConceptNode.Kind.CLASS)
            return failure(Status.OUT_OF_GRAMMAR,"La cible doit être une classe");
        RelationAssertion.Predicate predicate = a.kind() == ConceptNode.Kind.CLASS
                ? RelationAssertion.Predicate.SUBCLASS_OF : RelationAssertion.Predicate.INSTANCE_OF;
        if (universal && predicate != RelationAssertion.Predicate.SUBCLASS_OF)
            return failure(Status.OUT_OF_GRAMMAR,"Quantificateur universel appliqué à un individu");
        return new ParseResult(Status.PARSED, a.id(), predicate, b.id(),
                "Interprétation explicite : "+predicate+"("+a.id()+", "+b.id()+")");
    }
    private static String stripArticle(String s) {
        return s.strip().replaceFirst("^(?:le |la |les |l'|un |une |des )", "").strip();
    }
    private static String describe(String text, List<ConceptNode> nodes) {
        if (nodes.size() < 2) return text;
        return text + " = " + nodes.stream().map(n->n.id()+" ("+n.label()+")").toList();
    }
    private static ParseResult failure(Status status, String explain) {
        return new ParseResult(status,null,null,null,explain);
    }
}
