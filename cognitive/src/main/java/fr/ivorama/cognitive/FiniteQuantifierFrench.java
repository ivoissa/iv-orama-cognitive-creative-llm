package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.*;

/** IVOC-LING-009: quantificateurs FINIS, énoncés assertifs contrôlés.
 * Ne déduit jamais la négation du cas exclu par « sauf ».
 * Un modèle clos sur les noms déclarés n'est pas une hypothèse de monde clos sur les prédicats.
 */
public final class FiniteQuantifierFrench {
    private static final Map<String,String> PLURALS=Map.of(
        "documents","document", "dossiers","dossier", "portes","porte", "objets","objet");
    private static final Pattern NONE=Pattern.compile(
        "^(aucun|aucune) (document|dossier|porte|objet) n'est ([\\p{L}]+)$");
    private static final Pattern SOME=Pattern.compile(
        "^(certains|certaines) (documents|dossiers|portes|objets) sont ([\\p{L}]+)$");
    private static final Pattern EXCEPT=Pattern.compile(
        "^(tous|toutes) les (documents|dossiers|portes|objets) sont ([\\p{L}]+) sauf (le|la) " +
        "(document|dossier|porte|objet) ([a-z][a-z0-9]*)$");
    private static final Pattern EXCEPT_BEFORE=Pattern.compile(
        "^(tous|toutes) les (documents|dossiers|portes|objets) sauf (le|la) " +
        "(document|dossier|porte|objet) ([a-z][a-z0-9]*) sont ([\\p{L}]+)$");
    public record Parsed(ControlledFrench.Interpretation interpretation,Set<String> frozenKinds) {
        public Parsed { frozenKinds=Set.copyOf(frozenKinds); }
    }
    private final ControlledFrench language;
    public FiniteQuantifierFrench(ControlledFrench language) { this.language=Objects.requireNonNull(language); }
    public Optional<Parsed> parse(String source) {
        Objects.requireNonNull(source);
        String t=source.strip().toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ");
        if(!t.endsWith(".")) return Optional.empty(); // only an assertion, never a question
        t=t.substring(0,t.length()-1).strip();
        Matcher none=NONE.matcher(t);
        if(none.matches()) {
            String kind=none.group(2);
            checkSingular(none.group(1),kind);
            List<ControlledFrench.Entity> entities=members(kind);
            List<Formula> facts=new ArrayList<>();
            String concept=language.lexicon().resolve(none.group(3),kind,SemanticLexicon.Number.SINGULAR);
            for(var entity:entities) facts.add(Formula.not(atom(concept,entity)));
            return Optional.of(result(facts,kind,"Aucun : négation de chaque propriété pour le domaine explicitement déclaré"));
        }
        Matcher some=SOME.matcher(t);
        if(some.matches()) {
            String kind=PLURALS.get(some.group(2));
            checkSome(some.group(1),kind);
            List<ControlledFrench.Entity> entities=members(kind);
            // Le coeur n'a actuellement qu'une garantie 2-SAT ; ne pas encoder sans preuve
            // une disjonction >=3 dans un algorithme de décision 2-SAT.
            if(entities.size()!=2) throw new IllegalArgumentException(
                "CERTAINS limité à exactement 2 entités déclarées dans IVOC-LING-009");
            String concept=language.lexicon().resolve(some.group(3),kind,SemanticLexicon.Number.PLURAL);
            Formula a=atom(concept,entities.get(0)), b=atom(concept,entities.get(1));
            Formula disjunction=Formula.not(Formula.and(Formula.not(a),Formula.not(b)));
            return Optional.of(result(List.of(disjunction),kind,
                "Certains : disjonction existentielle sur exactement deux individus déclarés"));
        }
        Matcher except=EXCEPT.matcher(t);
        if(except.matches()) {
            return Optional.of(except(except.group(1),except.group(2),except.group(3),
                except.group(4),except.group(5),except.group(6)));
        }
        Matcher before=EXCEPT_BEFORE.matcher(t);
        if(before.matches()) {
            return Optional.of(except(before.group(1),before.group(2),before.group(6),
                before.group(3),before.group(4),before.group(5)));
        }
        return Optional.empty();
    }
    private Parsed except(String quantifier,String plural,String surface,String article,String excludedKind,String id) {
        String kind=PLURALS.get(plural);
        checkAll(quantifier,kind);
        if(!kind.equals(excludedKind)) throw new IllegalArgumentException("Exception de catégorie différente");
        String expected=kind.equals("porte")?"la":"le";
        if(!expected.equals(article))throw new IllegalArgumentException("Article de l'exception incorrect");
        var entities=members(kind);
        ControlledFrench.Entity excluded=new ControlledFrench.Entity(kind,id);
        if(!entities.contains(excluded))throw new IllegalArgumentException("Exception non déclarée : "+id);
        String concept=language.lexicon().resolve(surface,kind,SemanticLexicon.Number.PLURAL);
        List<Formula> facts=new ArrayList<>();
        for(var entity:entities) if(!entity.equals(excluded)) facts.add(atom(concept,entity));
        if(facts.isEmpty()) throw new IllegalArgumentException("Aucune entité non exceptée : éviter la clôture vacue");
        return result(facts,kind,"TOUS SAUF : propriété pour les autres; propriété de l'exclu INCONNUE");
    }
    private List<ControlledFrench.Entity> members(String kind) {
        var list=language.entities().stream().filter(e->e.kind().equals(kind)).toList();
        if(list.isEmpty())throw new IllegalArgumentException("Domaine "+kind+" vide : déclaration requise");
        if(list.size()>32)throw new IllegalArgumentException("Domaine trop grand : limite 32 sans instanciation partielle");
        return list;
    }
    private static void checkSingular(String word,String kind) {
        if(!word.equals(kind.equals("porte")?"aucune":"aucun"))
            throw new IllegalArgumentException("Accord AUCUN incorrect");
    }
    private static void checkSome(String word,String kind) {
        if(!word.equals(kind.equals("porte")?"certaines":"certains"))
            throw new IllegalArgumentException("Accord CERTAINS incorrect");
    }
    private static void checkAll(String word,String kind) {
        if(!word.equals(kind.equals("porte")?"toutes":"tous"))
            throw new IllegalArgumentException("Accord TOUS incorrect");
    }
    private static Formula atom(String concept,ControlledFrench.Entity e) {
        return Formula.atom(concept+"_"+e.kind()+"_"+e.name());
    }
    private static Parsed result(List<Formula> formulas,String kind,String meaning) {
        return new Parsed(new ControlledFrench.Interpretation(
            ControlledFrench.Act.ASSERT,formulas,meaning),Set.of(kind));
    }
}
