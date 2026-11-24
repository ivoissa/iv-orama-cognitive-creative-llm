package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IVOC-FND-005 : interprétation d'un français volontairement fermé, sans modèle neuronal.
 * Une phrase est acceptée uniquement si elle correspond exactement à une règle publiée.
 * Les règles « tous » sont développées sur un domaine FINI déclaré, ensuite verrouillé.
 * Cela ne constitue PAS une quantification sur un monde ouvert.
 */
public final class ControlledFrench {
    public enum Act { ASSERT, ASK, UNIVERSAL }
    public record Interpretation(Act act, List<Formula> formulas, String canonicalMeaning) {
        public Interpretation {
            Objects.requireNonNull(act); Objects.requireNonNull(canonicalMeaning);
            formulas = List.copyOf(formulas);
            if (formulas.isEmpty()) throw new IllegalArgumentException("Aucune formule produite");
        }
    }
    public record Entity(String kind, String name) {}

    private static final String NOUN = "(document|dossier|porte|objet)";
    private static final String IDENT = "([a-z][a-z0-9]*)";
    private static final String ADJ = "([\\p{L}]+)";
    private static final String SUBJ = "((?:(?:le|la)\\s+)|l')" + NOUN + "\\s+" + IDENT;
    private static final Pattern ASSERTION = Pattern.compile("^"+SUBJ+"\\s+(est|n'est pas)\\s+"+ADJ+"$");
    private static final Pattern INVERTED_QUESTION = Pattern.compile("^"+SUBJ+"\\s+(est-il|est-elle|n'est-il pas|n'est-elle pas)\\s+"+ADJ+"$");
    private static final Pattern CONDITIONAL = Pattern.compile("^si\\s+(.+?),\\s*alors\\s+(.+)$");
    private static final Pattern UNIVERSAL = Pattern.compile("^(tous|toutes)\\s+les\\s+(documents|dossiers|portes|objets)\\s+"+ADJ+"\\s+sont\\s+"+ADJ+"$");
    private static final Pattern SIMPLE_UNIVERSAL = Pattern.compile("^(tous|toutes)\\s+les\\s+(documents|dossiers|portes|objets)\\s+sont\\s+"+ADJ+"$");
    private final SemanticLexicon lexicon = new SemanticLexicon();
    public SemanticLexicon lexicon() { return lexicon; }
    private static final Map<String,String> PLURALS = Map.of("documents","document", "dossiers","dossier",
            "portes","porte", "objets","objet");
    private final LinkedHashSet<Entity> entities = new LinkedHashSet<>();
    private final Set<String> closedKinds = new HashSet<>();

    public void declareEntity(String kind, String name) {
        kind = normalize(kind); name = normalize(name);
        if (!kind.matches("document|dossier|porte|objet"))
            throw new IllegalArgumentException("Catégorie inconnue : " + kind);
        if (!name.matches("[a-z][a-z0-9]*"))
            throw new IllegalArgumentException("Identifiant d'entité invalide");
        if (Set.of("et","ou","si","est","sont","pas","ne","tous","toutes","alors","non").contains(name))
            throw new IllegalArgumentException("Mot-outil réservé comme identifiant d'entité : "+name);
        if (closedKinds.contains(kind))
            throw new IllegalStateException("Domaine '"+kind+"' figé par une règle TOUS ; aucune entité nouvelle autorisée");
        if (!entities.add(new Entity(kind,name))) throw new IllegalArgumentException("Entité déjà déclarée");
    }
    public Set<Entity> entities() { return Collections.unmodifiableSet(entities); }
    public Set<String> frozenKinds() { return Set.copyOf(closedKinds); }
    /** Gel simultané des domaines d'une règle quantifiée, uniquement après admission. */
    public void freezeKinds(Set<String> kinds) {
        if(kinds.isEmpty() || !Set.of("document","dossier","porte","objet").containsAll(kinds))
            throw new IllegalArgumentException("Catégorie de domaine inconnue");
        closedKinds.addAll(kinds);
    }


    public Interpretation interpret(String source) { return interpret(source,Map.of()); }
    /** Choix de sens locaux à UN acte interrogatif, sans mutation globale du dictionnaire. */
    public Interpretation interpret(String source, Map<String,String> chosen) {
        Objects.requireNonNull(chosen);
        String t = normalize(source);
        if (t.isBlank()) throw new GrammarValidationException("Énoncé vide");
        if (t.endsWith("?") || t.endsWith(".")) t = t.substring(0,t.length()-1).trim();
        if (t.startsWith("pourquoi "))
            throw new GrammarValidationException("Question POURQUOI non encore formalisée ; une réponse oui/non serait inadéquate");
        boolean question = source.strip().endsWith("?") || t.startsWith("est-ce que ") || t.startsWith("est ce que ");
        if ((t.startsWith("est-ce que ") || t.startsWith("est ce que ")) && !source.strip().endsWith("?"))
            throw new GrammarValidationException("Interrogation explicite sans point d’interrogation");
        if (t.startsWith("est-ce que ")) t = t.substring("est-ce que ".length());
        if (t.startsWith("est ce que ")) t = t.substring("est ce que ".length());
        Matcher inverted = INVERTED_QUESTION.matcher(t);
        if (inverted.matches()) {
            validateArticle(inverted.group(1),inverted.group(2));
            validateInversion(inverted.group(2),inverted.group(4));
            Formula f = makeFact(inverted.group(2),inverted.group(3),inverted.group(5),
                    inverted.group(4).startsWith("n'"),chosen);
            return new Interpretation(Act.ASK, List.of(f), "Question contrôlée : "+f);
        }
        Matcher conditional = CONDITIONAL.matcher(t);
        if (conditional.matches() && !question) {
            Formula a = parseFact(conditional.group(1),chosen);
            Formula b = parseFact(conditional.group(2),chosen);
            return new Interpretation(Act.ASSERT,List.of(Formula.implies(a,b)),
                    "Implication locale sur des entités déclarées");
        }
        Matcher simpleUniversal = SIMPLE_UNIVERSAL.matcher(t);
        if(simpleUniversal.matches() && !question) {
            String kind=PLURALS.get(simpleUniversal.group(2));
            validateUniversalDeterminer(kind,simpleUniversal.group(1));
            String prop=resolveWord(simpleUniversal.group(3),kind,SemanticLexicon.Number.PLURAL,chosen);
            List<Formula> result=new ArrayList<>();
            for(Entity entity:entities) if(entity.kind().equals(kind))
                result.add(atom(entity,prop));
            if(result.isEmpty()) throw new IllegalStateException("Déclarer une entité de la catégorie '"+kind+"' avant TOUS");
            return new Interpretation(Act.UNIVERSAL,result,
                    "Assertion universelle finie sur "+result.size()+" entité(s) ; domaine figé après admission");
        }
        Matcher universal = UNIVERSAL.matcher(t);
        if (universal.matches() && !question) {
            String kind = PLURALS.get(universal.group(2));
            validateUniversalDeterminer(kind,universal.group(1));
            String a = resolveWord(universal.group(3),kind,SemanticLexicon.Number.PLURAL,chosen);
            String b = resolveWord(universal.group(4),kind,SemanticLexicon.Number.PLURAL,chosen);
            if(a.equals(b)) throw new GrammarValidationException("La règle TOUS est tautologique et ne détermine rien ; domaine non figé");
            List<Formula> result = new ArrayList<>();
            for (Entity e : entities) if (e.kind().equals(kind))
                result.add(Formula.implies(atom(e,a),atom(e,b)));
            if (result.isEmpty()) throw new IllegalStateException("Déclarer au moins une entité de la catégorie '"+kind+"' avant TOUS");
            return new Interpretation(Act.UNIVERSAL,result,
                    "Règle TOUS instanciée sur "+result.size()+" entité(s) du domaine déclaré et désormais figé ; aucune universalité hors domaine");
        }
        Matcher assertion = ASSERTION.matcher(t);
        if (assertion.matches()) {
            validateArticle(assertion.group(1),assertion.group(2));
            Formula f = makeFact(assertion.group(2),assertion.group(3),assertion.group(5),
                    assertion.group(4).startsWith("n'"),chosen);
            return new Interpretation(question?Act.ASK:Act.ASSERT,List.of(f),
                    "Proposition locale : "+f);
        }
        throw new GrammarValidationException("Hors français contrôlé ou ambigu : '"+source+"'. Aucune interprétation inventée.");
    }
    /** L'analyse ne modifie jamais le domaine : validation/commit séparés. */
    public void commit(Interpretation interpretation) {
        Objects.requireNonNull(interpretation);
        if (interpretation.act()!=Act.UNIVERSAL) return;
        // Les domaines de toutes les clauses de cette règle sont homogènes.
        Formula first=interpretation.formulas().get(0);
        Formula.Atom a;
        if(first instanceof Formula.Implies implication && implication.premise() instanceof Formula.Atom atom)
            a=atom;
        else if(first instanceof Formula.Atom atom) a=atom;
        else throw new IllegalArgumentException("Règle universelle non conforme");
        for (String kind : List.of("document","dossier","porte","objet")) {
            if(a.name().contains("_"+kind+"_")) { closedKinds.add(kind); return; }
        }
        throw new IllegalArgumentException("Catégorie de règle indéterminée");
    }
    private Formula parseFact(String text, Map<String,String> chosen) {
        Matcher m = ASSERTION.matcher(text.strip());
        if (!m.matches()) throw new GrammarValidationException("Proposition de condition invalide : "+text);
        validateArticle(m.group(1),m.group(2));
        return makeFact(m.group(2),m.group(3),m.group(5),m.group(4).startsWith("n'"),chosen);
    }
    private static void validateUniversalDeterminer(String kind,String determiner) {
        if(!(kind.equals("porte") ? determiner.equals("toutes") : determiner.equals("tous")))
            throw new GrammarValidationException("Accord quantificateur/catégorie invalide");
    }
    private static void validateInversion(String kind, String auxiliary) {
        boolean feminine=kind.equals("porte");
        boolean correct=feminine ? auxiliary.contains("-elle") : auxiliary.contains("-il");
        if(!correct) throw new GrammarValidationException("Accord pronominal de l'inversion invalide");
    }
    private static void validateArticle(String article, String kind) {
        if (!(kind.equals("porte") ? article.strip().equals("la")
                : article.strip().equals("le") || (kind.equals("objet") && article.equals("l'"))))
            throw new GrammarValidationException("Accord article/catégorie invalide");
    }
    private String resolveWord(String adjective, String kind, SemanticLexicon.Number number, Map<String,String> chosen) {
        String selected=chosen.get(adjective);
        return selected==null ? lexicon.resolve(adjective,kind,number)
                : lexicon.resolveSelected(adjective,kind,number,selected);
    }
    private Formula makeFact(String kind, String name, String adj, boolean neg, Map<String,String> chosen) {
        Entity e = new Entity(kind,name);
        if (!entities.contains(e))
            throw new GrammarValidationException("Entité non déclarée : "+kind+" "+name);
        Formula atom = atom(e,resolveWord(adj,kind,SemanticLexicon.Number.SINGULAR,chosen));
        return neg ? Formula.not(atom) : atom;
    }
    private static Formula atom(Entity e, String prop) {
        return Formula.atom(prop+"_"+e.kind()+"_"+e.name());
    }
    public String questionFor(String kind, String id, String adjective) {
        Entity e = new Entity(normalize(kind),normalize(id));
        if (!entities.contains(e)) throw new IllegalArgumentException("Entité non déclarée");
        String article = e.kind().equals("porte") ? "La" : "Le";
        String inversion = e.kind().equals("porte") ? "est-elle" : "est-il";
        adjective = lexicon.surfaceFor(normalize(adjective),e.kind(),SemanticLexicon.Number.SINGULAR);
        String surface = article+" "+e.kind()+" "+id+" "+inversion+" "+adjective+" ?";
        interpret(surface); // garantie de réanalyse : échec bruyant au lieu d'émettre un texte invalide
        return surface;
    }
    private static String normalize(String text) {
        return Objects.requireNonNull(text).strip().toLowerCase(Locale.ROOT)
                .replace('’','\'').replaceAll("\\s+"," ");
    }
}
