package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.*;

/** IVOC-LING-008. Universelle à restricteur relationnel, sur DOMAINES FINIS figés.
 * Règle: forall x in S : (exists y in O : R(x,y)) => P(x).
 * Normalisation exacte sur le domaine fini en AND_{x,y}(R(x,y) => P(x)).
 * Cette transformation n'affirme jamais R(x,y), ni P(x), ni l'existence d'un y.
 */
public final class QuantifiedRelationalFrench {
    private static final Map<String,String> PLURALS=Map.of(
        "documents","document", "dossiers","dossier", "portes","porte", "objets","objet");
    private static final Pattern RULE=Pattern.compile(
        "^(tous|toutes) les (documents|dossiers|portes|objets) qui ([\\p{L}]+) (un|une) " +
        "(document|dossier|porte|objet) sont ([\\p{L}]+)$");
    private final ControlledFrench language;
    private final RelationalFrench relations;
    public QuantifiedRelationalFrench(ControlledFrench language,RelationalFrench relations) {
        this.language=Objects.requireNonNull(language);
        this.relations=Objects.requireNonNull(relations);
    }
    public record Parsed(ControlledFrench.Interpretation interpretation,Set<String> frozenKinds) {
        public Parsed { frozenKinds=Set.copyOf(frozenKinds); }
    }
    public Optional<Parsed> parse(String phrase) {
        String t=phrase.strip().toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ");
        if(t.endsWith(".")) t=t.substring(0,t.length()-1);
        Matcher m=RULE.matcher(t);
        if(!m.matches())return Optional.empty();
        String subject=PLURALS.get(m.group(2));String object=m.group(5);
        if(!m.group(1).equals(subject.equals("porte")?"toutes":"tous"))
            throw new IllegalArgumentException("Accord du quantificateur incorrect");
        if(!m.group(4).equals(object.equals("porte")?"une":"un"))
            throw new IllegalArgumentException("Accord du complément incorrect");
        List<RelationalFrench.Entry> candidates=relations.entries().stream()
            .filter(e->e.subjectKind().equals(subject)&&e.objectKind().equals(object)
              &&relations.activePlural(e.concept()).filter(m.group(3)::equals).isPresent()).toList();
        if(candidates.size()!=1)
            throw new IllegalArgumentException("Relation plurielle absente ou ambiguë; préciser une forme lexicale");
        String concept=candidates.get(0).concept();
        String property=language.lexicon().resolve(m.group(6),subject,SemanticLexicon.Number.PLURAL);
        List<ControlledFrench.Entity> xs=language.entities().stream().filter(e->e.kind().equals(subject)).toList();
        List<ControlledFrench.Entity> ys=language.entities().stream().filter(e->e.kind().equals(object)).toList();
        if(xs.isEmpty()||ys.isEmpty())
            throw new IllegalArgumentException("Déclarer au moins une entité dans chaque domaine avant cette universelle finie");
        if((long)xs.size()*ys.size()>1024)
            throw new IllegalArgumentException("Budget d'instanciation relationnelle dépassé; aucune règle partielle admise");
        List<Formula> formulas=new ArrayList<>();
        for(var x:xs)for(var y:ys) {
            Formula relation=Formula.atom("relation_"+concept+"_"+subject+"_"+x.name()+"_"+object+"_"+y.name());
            Formula consequence=Formula.atom(property+"_"+subject+"_"+x.name());
            formulas.add(Formula.implies(relation,consequence));
        }
        return Optional.of(new Parsed(new ControlledFrench.Interpretation(
            ControlledFrench.Act.UNIVERSAL,formulas,
            "∀x∈"+subject+" ((∃y∈"+object+" R(x,y))⇒P(x)), portée finie déclarée"),
            Set.of(subject,object)));
    }
}
