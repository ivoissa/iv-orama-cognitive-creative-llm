package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IVOC-FND-005/LING-001 : composition sémantique vérifiable d'un français contrôlé.
 * Les feuilles sont exclusivement validées par ControlledFrench, le seul constructeur
 * de sens supplémentaire est la conjonction de phrases ou de prédicats homologues.
 * Aucun modèle probabiliste, aucune interprétation implicite d'une ambiguïté.
 */
public final class CompositionalFrench {
    private CompositionalFrench() {}

    /** AST typé : toute production porte sa formule et les feuilles d'origine. */
    public sealed interface Node permits Leaf, Conjunction {
        Formula formula();
        int leaves();
    }
    public record Leaf(String source, Formula formula) implements Node {
        public Leaf { Objects.requireNonNull(source); Objects.requireNonNull(formula); }
        @Override public int leaves() { return 1; }
    }
    public record Conjunction(Node left, Node right) implements Node {
        public Conjunction { Objects.requireNonNull(left); Objects.requireNonNull(right); }
        @Override public Formula formula() { return Formula.and(left.formula(),right.formula()); }
        @Override public int leaves() { return left.leaves()+right.leaves(); }
    }
    public record Analysis(ControlledFrench.Act act, Node tree, String reading) {
        public Analysis { Objects.requireNonNull(act); Objects.requireNonNull(tree); Objects.requireNonNull(reading); }
        public ControlledFrench.Interpretation interpretation() {
            return new ControlledFrench.Interpretation(act,List.of(tree.formula()),reading);
        }
    }

    private static final String SUBJECT = "((?:(?:le|la)\\s+|l')(?:document|dossier|porte|objet)\\s+[a-z][a-z0-9]*)";
    private static final String ADJ = "([\\p{L}]+)";
    private static final Pattern PREDICATE_COORD = Pattern.compile("^"+SUBJECT+"\\s+(est|est-il|est-elle)\\s+"+ADJ+"\\s+et\\s+"+ADJ+"$");
    private static final Pattern FULL_CLAUSE_START = Pattern.compile("^(?:(?:le|la)\\s+|l')(?:document|dossier|porte|objet)\\s+[a-z][a-z0-9]*\\s+(?:est|n'est pas)\\s+.*$");

    public static Analysis analyse(ControlledFrench language, String input) {
        return analyse(language,input,Map.of());
    }
    public static Analysis analyse(ControlledFrench language, String input, Map<String,String> chosen) {
        Objects.requireNonNull(language); Objects.requireNonNull(input);
        Objects.requireNonNull(chosen);
        if (!normalize(input).contains(" et ")) {
            var i=language.interpret(input,chosen);
            if(i.formulas().size()!=1) throw new IllegalArgumentException("Composition multiple réservée aux propositions unitaires");
            return new Analysis(i.act(),new Leaf(input,i.formulas().get(0)),i.canonicalMeaning());
        }
        String normalized=normalize(input);
        boolean question=normalized.endsWith("?");
        String body=normalized.replaceFirst("[?.]$","").strip();
        boolean prefix=false;
        if(body.startsWith("est-ce que ")) { body=body.substring(11); prefix=true; }
        else if(body.startsWith("est ce que ")) { body=body.substring(11); prefix=true; }
        if(prefix && !question) throw new IllegalArgumentException("L'interrogation explicite exige le point d'interrogation");
        if(body.startsWith("pourquoi ") || body.startsWith("si ") || body.startsWith("tou"))
            throw new IllegalArgumentException("Portée de coordination non prise en charge, clarification exigée");
        if (body.contains(" ou ")) throw new IllegalArgumentException("Coordination mixte et/ou ambiguë, clarification exigée");
        Map<String,Integer> occurrences=new HashMap<>();
        Matcher sameSubject=PREDICATE_COORD.matcher(body);
        if(sameSubject.matches()) {
            String subject=sameSubject.group(1), verb=sameSubject.group(2);
            if ((verb.contains("-il") || verb.contains("-elle")) && !question)
                throw new IllegalArgumentException("Inversion interrogative sans question");
            if (verb.contains("-") && (subject.startsWith("la porte ") != verb.contains("-elle")))
                throw new IllegalArgumentException("Inversion interrogative incompatible avec le genre du sujet");
            var a=singleFact(language,subject+" est "+sameSubject.group(3),
                    choiceFor(sameSubject.group(3),chosen,occurrences));
            var b=singleFact(language,subject+" est "+sameSubject.group(4),
                    choiceFor(sameSubject.group(4),chosen,occurrences));
            return new Analysis(question?ControlledFrench.Act.ASK:ControlledFrench.Act.ASSERT,
                    new Conjunction(a,b),"Conjonction de propriétés de même sujet");
        }
        // Les coordinations avec une négation elliptique, ou avec une portée floue,
        // ne doivent PAS être converties arbitrairement en deux faits.
        String[] chunks=body.split("\\s+et\\s+",-1);
        if(chunks.length<2 || chunks.length>8)
            throw new IllegalArgumentException("Coordination hors domaine (2 à 8 clauses)");
        Node composed=null;
        for(String chunk:chunks) {
            if (!FULL_CLAUSE_START.matcher(chunk).matches())
                throw new IllegalArgumentException("La coordination exige des propositions complètes : "+chunk);
            String[] tokens=chunk.strip().split("\\s+");
            String adjective=tokens[tokens.length-1];
            Leaf leaf=singleFact(language,chunk,choiceFor(adjective,chosen,occurrences));
            composed=(composed==null)?leaf:new Conjunction(composed,leaf);
        }
        return new Analysis(question?ControlledFrench.Act.ASK:ControlledFrench.Act.ASSERT,
                composed,"Conjonction de "+composed.leaves()+" propositions contrôlées");
    }
    /** Chaque occurrence d'un adjectif possède un sens local et indépendant. */
    private static Map<String,String> choiceFor(String surface, Map<String,String> chosen,
                                                 Map<String,Integer> counters) {
        int index=counters.merge(surface,1,Integer::sum)-1;
        String selected=chosen.getOrDefault(surface+"#"+index,chosen.get(surface));
        return selected==null?Map.of():Map.of(surface,selected);
    }
    private static Leaf singleFact(ControlledFrench language,String phrase, Map<String,String> chosen) {
        var i=language.interpret(phrase+".",chosen);
        if(i.act()!=ControlledFrench.Act.ASSERT || i.formulas().size()!=1)
            throw new IllegalArgumentException("Une feuille doit être une proposition contrôlée : "+phrase);
        return new Leaf(phrase,i.formulas().get(0));
    }
    private static String normalize(String t) {
        return t.strip().toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ");
    }
}
