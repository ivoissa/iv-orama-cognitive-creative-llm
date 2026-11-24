package fr.ivorama.cognitive;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/**
 * IVOC-ACT-001 : reconnaissance d'un acte DIRECTIF (demande de production ou d'analyse)
 * par CONSTRUCTION linguistique — impératif, requête polie, souhait adressé — jamais par
 * correspondance avec une phrase entière.
 *
 * Un acte directif n'est pas une question : il n'ouvre aucune faille interrogative f_Q,
 * donc aucun QUESTION_POSED n'est journalisé. Cette classe ne produit AUCUN texte demandé ;
 * elle borne seulement l'entrée afin de ne plus confondre « Écris… » avec une question logique.
 * Le lexique associe un verbe à un genre d'acte, jamais une demande à une réponse.
 */
public final class DirectiveActs {
    private DirectiveActs() {}

    public enum Kind { CREATE, ANALYZE, EXPLAIN, REFORMULATE }

    public record Directive(Kind kind, String verb, String construction, String object, boolean mixed,
                            String sourceText, int start, int end, List<String> unresolved) {
        public Directive {
            Objects.requireNonNull(kind); Objects.requireNonNull(verb); Objects.requireNonNull(construction);
            Objects.requireNonNull(object); Objects.requireNonNull(sourceText); unresolved=List.copyOf(unresolved);
            if(start<0||end<start||end>sourceText.length())throw new IllegalArgumentException("Position d'acte invalide");
        }
        public Directive(Kind kind,String verb,String construction,String object,boolean mixed) {
            this(kind,verb,construction,object,mixed,object,0,object.length(),List.of());
        }
        public String json() {
            return StructuredJsonWriter.json(Map.of("schema","ivoc.directive.1","kind",kind.name(),"verb",verb,
                "construction",construction,"object",object,"mixed",mixed,"sourceText",sourceText,
                "span",Map.of("start",start,"end",end,"excerpt",sourceText.substring(start,end)),"unresolved",unresolved));
        }
        public String describe() {
            return "Acte directif reconnu : " + kind + " (verbe « " + verb + " », construction : " + construction
                + (object.isBlank() ? "" : ", objet : « " + object + " »") + ").\n"
                + (mixed ? "Demande composite : contexte complet conservé ; routage suspendu, aucun sous-acte exécuté.\n"
                         : "Cette demande n'est pas évaluée comme une proposition logique ; aucune faille interrogative n'est ouverte.\n")
                + (unresolved.isEmpty()?"":"À préciser : "+unresolved+"\n")
                + "Classification seulement ; l'exécution appartient au service de tâches textuelles.\nJSON de demande :\n"+json();
        }
    }

    /** Formes : infinitif, impératif 2sg, impératif 2pl, subjonctif 2sg, subjonctif 2pl. */
    private record Verb(Kind kind, String lemma) {}
    private static final Map<String, Verb> INFINITIVE = new HashMap<>();
    private static final Map<String, Verb> IMPERATIVE = new HashMap<>();
    private static final Map<String, Verb> SUBJUNCTIVE = new HashMap<>();
    private static final Map<String,String> SUBJUNCTIVE_PERSON = new HashMap<>();

    private static void add(Kind kind, String inf, String imp2s, String imp2p, String sub2s, String sub2p) {
        Verb v = new Verb(kind, inf);
        INFINITIVE.put(fold(inf), v);
        IMPERATIVE.put(fold(imp2s), v); IMPERATIVE.put(fold(imp2p), v);
        SUBJUNCTIVE.put(fold(sub2s), v); SUBJUNCTIVE.put(fold(sub2p), v);
        SUBJUNCTIVE_PERSON.put(fold(sub2s),"tu"); SUBJUNCTIVE_PERSON.put(fold(sub2p),"vous");
    }
    static {
        add(Kind.CREATE, "écrire", "écris", "écrivez", "écrives", "écriviez");
        add(Kind.CREATE, "composer", "compose", "composez", "composes", "composiez");
        add(Kind.CREATE, "rédiger", "rédige", "rédigez", "rédiges", "rédigiez");
        add(Kind.CREATE, "inventer", "invente", "inventez", "inventes", "inventiez");
        add(Kind.CREATE, "créer", "crée", "créez", "crées", "créiez");
        add(Kind.CREATE, "générer", "génère", "générez", "génères", "génériez");
        add(Kind.CREATE, "imaginer", "imagine", "imaginez", "imagines", "imaginiez");
        add(Kind.CREATE, "décrire", "décris", "décrivez", "décrives", "décriviez");
        add(Kind.CREATE, "raconter", "raconte", "racontez", "racontes", "racontiez");
        add(Kind.ANALYZE, "analyser", "analyse", "analysez", "analyses", "analysiez");
        add(Kind.ANALYZE, "commenter", "commente", "commentez", "commentes", "commentiez");
        add(Kind.ANALYZE, "interpréter", "interprète", "interprétez", "interprètes", "interprétiez");
        add(Kind.EXPLAIN, "définir", "définis", "définissez", "définisses", "définissiez");
        add(Kind.EXPLAIN, "expliquer", "explique", "expliquez", "expliques", "expliquiez");
        add(Kind.REFORMULATE, "reformuler", "reformule", "reformulez", "reformules", "reformuliez");
        add(Kind.REFORMULATE, "résumer", "résume", "résumez", "résumes", "résumiez");
        add(Kind.REFORMULATE, "paraphraser", "paraphrase", "paraphrasez", "paraphrases", "paraphrasiez");
        add(Kind.REFORMULATE, "traduire", "traduis", "traduisez", "traduises", "traduisiez");
    }

    private static final Pattern POLITE_PREFIX = Pattern.compile("^(?:s'il (?:te|vous) pla[iî]t|stp|svp)\\s*,?\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REQUEST = Pattern.compile(
        "^(?:(?:peux|pourrais|veux|voudrais)-tu|(?:pouvez|pourriez|voulez|voudriez)-vous"
        + "|est[ -]ce que (?:tu (?:peux|pourrais|veux|voudrais)|vous (?:pouvez|pourriez|voulez|voudriez))"
        + "|tu (?:peux|pourrais)|vous (?:pouvez|pourriez))\\s+(?:bien\\s+|donc\\s+)?([\\p{L}]+)(?:\\s+(.*))?$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SUBJECT_FOLLOWERS = Pattern.compile("^(?:est|sont|etait|etaient|sera|seront|a|ont|n'est|n'a|ne)\\b");
    private static final Pattern WISH = Pattern.compile(
        "^(?:j'aimerais|j'aimerai|je voudrais|je souhaiterais|je souhaite|je veux|je d[ée]sire|je d[ée]sirerais)\\s+(?:bien\\s+)?"
        + "que (tu|vous)\\s+([\\p{L}]+)(?:\\s+(.*))?$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Segmentation à portée explicite : ponctuation citée ou parenthésée jamais exécutée. */
    public static Optional<Directive> classify(String text) {
        if(text==null||text.isBlank())return Optional.empty();
        if(text.length()>8000){
            String prefix=text.substring(0,Math.min(300,text.length()));
            return sentence(normalize(prefix)).map(d->new Directive(d.kind(),d.verb(),d.construction(),d.object(),false,
                text,0,prefix.length(),List.of("Budget d'entrée dépassé : "+text.length()+" caractères, maximum 8000 ; seul le préfixe a été classé")));
        }
        var scan=DiscourseSegments.scan(text);
        Directive first=null; DiscourseSegments.Span selected=null;
        for(var span:scan.spans()) {
            var found=sentence(normalize(text.substring(span.start(),span.end())));
            if(found.isPresent()){first=found.get();selected=span;break;}
        }
        if(first==null)return Optional.empty();
        var issues=new ArrayList<>(scan.unresolved());
        if(first.object().isBlank())issues.add("Objet de la demande manquant");
        boolean mixed=scan.spans().size()>1;
        if(mixed)issues.add("Plusieurs actes ou segments : composition de tâches non encore exécutée");
        // L'objet conserve exactement sa casse et son contenu dans la source.
        String segment=text.substring(selected.start(),selected.end());
        String originalObject=first.object().isBlank()?"":originalTail(segment,first.object());
        return Optional.of(new Directive(first.kind(),first.verb(),first.construction(),originalObject,mixed,
            text,selected.start(),selected.end(),issues));
    }
    /** Relecture stricte : champs dérivés contrôlés contre la source, sans nouvel acte Q. */
    public static Directive read(String json) {
        if(json==null||json.length()>64000)throw new IllegalArgumentException("Demande JSON absente ou hors budget");
        Object value=MiniJson.parse(json);
        if(!(value instanceof Map<?,?> m)||!(m.get("sourceText") instanceof String source))
            throw new IllegalArgumentException("Source de demande requise");
        var directive=classify(source).orElseThrow(()->new IllegalArgumentException("Source non directive"));
        if(!MiniJson.parse(directive.json()).equals(value))throw new IllegalArgumentException("Demande JSON incohérente avec sa source");
        return directive;
    }
    private static String originalTail(String source,String normalizedTail) {
        // La normalisation utilisée ici préserve la longueur (casse/apostrophe seulement).
        String comparable=normalize(source);
        int offset=comparable.length()-normalizedTail.length();
        return source.strip().substring(offset);
    }

    private static Optional<Directive> sentence(String s) {
        String body = POLITE_PREFIX.matcher(s).replaceFirst("");
        Matcher request = REQUEST.matcher(body);
        if (request.matches()) {
            Verb v = INFINITIVE.get(fold(request.group(1)));
            if (v != null) return Optional.of(new Directive(v.kind(), v.lemma(), "requête polie", request.group(2)==null?"":request.group(2), false));
            return Optional.empty();
        }
        Matcher wish = WISH.matcher(body);
        if (wish.matches()) {
            Verb v = SUBJUNCTIVE.get(fold(wish.group(2)));
            if (v != null && agreesWish(wish.group(1),wish.group(2))) return Optional.of(new Directive(v.kind(), v.lemma(), "souhait adressé", wish.group(3)==null?"":wish.group(3), false));
            return Optional.empty();
        }
        String[] words = body.split("\\s+", 2);
        Verb v = IMPERATIVE.get(fold(words[0]).replaceFirst("-(?:moi|nous)$",""));
        // Un impératif exige un complément ; un mot isolé reste hors de cet acte.
        // « Compose est un mot » : un verbe suivi d'une copule ou d'un auxiliaire est un sujet, pas un impératif.
        if (v != null && words.length == 2 && !words[1].isBlank() && !SUBJECT_FOLLOWERS.matcher(fold(words[1])).find())
            return Optional.of(new Directive(v.kind(), v.lemma(), "impératif", words[1], false));
        return Optional.empty();
    }

    private static boolean agreesWish(String subject,String verb) {return subject.toLowerCase(Locale.ROOT).equals(SUBJUNCTIVE_PERSON.get(fold(verb)));}
    private static String normalize(String raw) {
        return raw.strip().replace('’', '\'');
    }
    /** Pliage des accents : « Écris » et « ecris » désignent la même forme. */
    private static String fold(String s) {
        return Normalizer.normalize(normalize(s).toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
