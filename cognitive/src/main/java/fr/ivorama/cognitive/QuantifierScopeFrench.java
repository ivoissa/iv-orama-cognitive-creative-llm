package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.*;

/** IVOC-LING-004: deux lectures EXPLICITES d'une négation sous quantificateur fini.
 * Sans choix utilisateur, aucune proposition n'est imputée à la question.
 * Les entités sont figées pour CET acte Q, pas dans le registre global.
 */
public final class QuantifierScopeFrench {
    private QuantifierScopeFrench() {}
    private static final Pattern FORM = Pattern.compile(
        "^est(?:-ce| ce) que (tous|toutes) les (documents|dossiers|portes|objets) ne sont pas ([\\p{L}]+) \\?$"
    );
    private static final Map<String,String> SINGULAR = Map.of(
        "documents", "document", "dossiers", "dossier", "portes", "porte", "objets", "objet"
    );
    public record Readings(Formula notAll, Formula none, String labelNotAll, String labelNone,
                           int domainSize, String lexicalFingerprint) {
        public Readings {
            Objects.requireNonNull(notAll); Objects.requireNonNull(none);
            Objects.requireNonNull(labelNotAll); Objects.requireNonNull(labelNone);
            Objects.requireNonNull(lexicalFingerprint);
            if (domainSize < 2 || notAll.equals(none))
                throw new IllegalArgumentException("Deux lectures distinctes attendues");
        }
        public Formula select(String choice) {
            return switch(choice) {
                case "pas_tous" -> notAll;
                case "aucun" -> none;
                default -> throw new IllegalArgumentException("Lecture inconnue : " + choice);
            };
        }
    }
    /** Si le motif n'est pas celui du fragment, retourner vide : aucun sens inventé. */
    public static Optional<Readings> detect(ControlledFrench language, String phrase) {
        return detect(language, phrase, Map.of());
    }
    public static Optional<Readings> detect(ControlledFrench language, String phrase, Map<String,String> decisions) {
        Objects.requireNonNull(language); Objects.requireNonNull(phrase); Objects.requireNonNull(decisions);
        String source = phrase.strip().toLowerCase(Locale.ROOT)
                .replace('’','\'').replaceAll("\\s+", " ");
        Matcher matcher = FORM.matcher(source);
        if (!matcher.matches()) return Optional.empty();
        String plural=matcher.group(2), singular=SINGULAR.get(plural);
        if (!(singular.equals("porte") ? matcher.group(1).equals("toutes") : matcher.group(1).equals("tous")))
            throw new IllegalArgumentException("Accord du quantificateur incorrect");
        List<ControlledFrench.Entity> entities=language.entities().stream()
                .filter(e->e.kind().equals(singular)).toList();
        if(entities.size()>32) throw new IllegalArgumentException(
                "Portée finie limitée à 32 entités pour ce prototype ; aucune réponse n’est devinée");
        if(entities.size()<2) throw new IllegalArgumentException(
                "Au moins deux entités déclarées sont nécessaires pour distinguer ces portées ; aucune lecture forcée");
        String selected=decisions.get(matcher.group(3));
        String concept=selected==null
                ? language.lexicon().resolve(matcher.group(3),singular,SemanticLexicon.Number.PLURAL)
                : language.lexicon().resolveSelected(matcher.group(3),singular,SemanticLexicon.Number.PLURAL,selected);
        List<Formula> atoms=new ArrayList<>();
        for(var e:entities) atoms.add(Formula.atom(concept+"_"+e.kind()+"_"+e.name()));
        Formula conjunction=atoms.get(0);
        Formula none=Formula.not(atoms.get(0));
        for(int i=1;i<atoms.size();i++) {
            conjunction=Formula.and(conjunction,atoms.get(i));
            none=Formula.and(none,Formula.not(atoms.get(i)));
        }
        Formula notAll=Formula.not(conjunction);
        // Une lecture déjà choisie peut rester sans forme UNIVOQUE en français.
        // L'étiquette est une aide d'affichage, non une nouvelle analyse : ne pas
        // faire échouer l'obligation FCI si la génération inverse est ambiguë.
        String singularAdjective;
        try { singularAdjective=language.lexicon().surfaceFor(concept,singular,SemanticLexicon.Number.SINGULAR); }
        catch(IllegalArgumentException ex) { singularAdjective=matcher.group(3)+" [sens "+concept+"]"; }
        return Optional.of(new Readings(notAll,none,
                "Pas tous : au moins un "+singular+" n'est pas "+singularAdjective+" (concept "+concept+")",
                "Aucun : chaque "+singular+" n'est pas "+singularAdjective+" (concept "+concept+")",
                entities.size(),language.lexicon().fingerprint()));
    }
}
