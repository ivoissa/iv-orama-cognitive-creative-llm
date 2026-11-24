package fr.ivorama.cognitive;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.*;

/** LING-006: relation binaire typée, formes active et passive, sans équivalence tacite. */
public final class RelationalFrench {
    public record Entry(String concept, String subjectKind, String objectKind,
                        String activeVerb, String passiveVerb) {}
    public record Analysis(SemanticIntermediateRepresentation.RelationFrame frame,
                           Formula proposition, ControlledFrench.Act act) {}
    private static final Pattern ENTITY=Pattern.compile("^(le|la) (document|dossier|porte|objet) ([a-z][a-z0-9]*)$");
    private static final Pattern SUBJECT=Pattern.compile("^(le|la) (document|dossier|porte|objet) ([a-z][a-z0-9]*) (.+)$");
    private static final Pattern CONDITIONAL=Pattern.compile("^si (.+?), alors (.+)$");
    private final ControlledFrench language;
    private final List<Entry> entries=new ArrayList<>();
    private final Map<String,String> pluralVerbs=new LinkedHashMap<>();
    public RelationalFrench(ControlledFrench language) {
        this.language=Objects.requireNonNull(language);
        register("contient","document","dossier","contient","est contenu dans","contiennent");
        register("cite","document","document","cite","est cité par","citent");
    }
    public List<Entry> entries() {return List.copyOf(entries);}
    /** Morphologie déclarée, pas de conjugaison devinée par le moteur. */
    public Optional<String> activePlural(String concept) {
        return Optional.ofNullable(pluralVerbs.get(concept));
    }
    public void register(String concept,String subjectKind,String objectKind,
                         String activeVerb,String passiveVerb,String activePlural) {
        String form=normal(activePlural);
        if(!form.matches("[\\p{L}]+"))
            throw new IllegalArgumentException("Pluriel relationnel invalide");
        String old=pluralVerbs.get(normal(concept));
        if(old!=null && !old.equals(form))
            throw new IllegalArgumentException("Flexion plurielle contradictoire pour "+concept);
        register(concept,subjectKind,objectKind,activeVerb,passiveVerb);
        pluralVerbs.put(normal(concept),form);
    }
    /** Empreinte stable du contenu lexical : intégrité, ni authenticité ni vérité. */
    public String fingerprint() {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            List<String> values=new ArrayList<>();
            for(var e:entries) values.add(String.join("\u0000",e.concept(),e.subjectKind(),
                    e.objectKind(),e.activeVerb(),e.passiveVerb(),pluralVerbs.getOrDefault(e.concept(),"")));
            Collections.sort(values);
            for(String value:values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte)0x0A);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    /** Lexical registration changes neither propositions nor FCI acts. */
    public void register(String concept,String subjectKind,String objectKind,
                         String activeVerb,String passiveVerb) {
        concept=normal(concept);subjectKind=normal(subjectKind);objectKind=normal(objectKind);
        activeVerb=normal(activeVerb);passiveVerb=normal(passiveVerb);
        if(!concept.matches("[a-z][a-z0-9_]*") || !subjectKind.matches("document|dossier|porte|objet")
           || !objectKind.matches("document|dossier|porte|objet")
           || !activeVerb.matches("[\\p{L}]+")
           || !passiveVerb.matches("est [\\p{L}]+ (dans|par)"))
            throw new IllegalArgumentException("Verbe ou sélection des rôles non reconnu");
        for(Entry existing:entries) {
            if(existing.concept().equals(concept)) {
                if(existing.subjectKind().equals(subjectKind)&&existing.objectKind().equals(objectKind)
                        &&existing.activeVerb().equals(activeVerb)&&existing.passiveVerb().equals(passiveVerb)) return;
                throw new IllegalArgumentException("Concept relationnel déjà défini avec une autre signature");
            }
            if((existing.activeVerb().equals(activeVerb)||existing.passiveVerb().equals(passiveVerb))
                &&existing.subjectKind().equals(subjectKind)&&existing.objectKind().equals(objectKind))
                throw new IllegalArgumentException("Collision lexicale relationnelle");
        }
        entries.add(new Entry(concept,subjectKind,objectKind,activeVerb,passiveVerb));
    }
    public boolean looksRelational(String phrase) {
        String t=normal(phrase);
        for(Entry e:entries) {
            if(t.contains(" "+e.activeVerb()+" ")||t.contains(" "+e.activeVerb()+"-il ")
                ||t.contains(" "+e.activeVerb()+"-elle ")
                ||t.contains(" "+e.passiveVerb()+" ")
                ||t.contains(" "+e.passiveVerb().substring(4)+" ")) return true;
        }
        return false;
    }
    public Optional<ControlledFrench.Interpretation> tryConditional(String phrase) {
        String t=normal(phrase);
        if(t.endsWith("?")) return Optional.empty();
        t=t.replaceFirst("\\.$","");
        Matcher m=CONDITIONAL.matcher(t);
        if(!m.matches() || (!looksRelational(m.group(1))&&!looksRelational(m.group(2))))
            return Optional.empty();
        Formula a=single(m.group(1)),b=single(m.group(2));
        return Optional.of(new ControlledFrench.Interpretation(ControlledFrench.Act.ASSERT,
                List.of(Formula.implies(a,b)),"Implication explicite de clauses typées"));
    }
    private Formula single(String phrase) {
        if(looksRelational(phrase)) return analyse(phrase).proposition();
        var unary=language.interpret(phrase+".");
        if(unary.act()!=ControlledFrench.Act.ASSERT||unary.formulas().size()!=1)
            throw new IllegalArgumentException("Condition hors fragment atomique");
        return unary.formulas().get(0);
    }
    public ControlledFrench.Interpretation interpret(String phrase) {
        Analysis a=analyse(phrase);
        return new ControlledFrench.Interpretation(a.act(),List.of(a.proposition()),
                "RSI relation orientée : "+a.frame());
    }
    public Analysis analyse(String phrase) {
        String t=normal(phrase);
        boolean question=t.endsWith("?");
        t=t.replaceFirst("[?.]$","").strip();
        boolean prefix=t.startsWith("est-ce que ")||t.startsWith("est ce que ");
        if(prefix) {
            if(!question) throw new IllegalArgumentException("Interrogation explicite sans '?'");
            t=t.substring(11);
        }
        // Passive interrogative inversion is a surface transformation only:
        // it does not change the subject/object roles of the semantic frame.
        Matcher inverted=Pattern.compile("^(le|la) (document|dossier|porte|objet) ([a-z][a-z0-9]*) est-(il|elle) (.+)$").matcher(t);
        if(inverted.matches()) {
            if(!question) throw new IllegalArgumentException("Inversion sans acte interrogatif");
            if(!inverted.group(4).equals(inverted.group(2).equals("porte")?"elle":"il"))
                throw new IllegalArgumentException("Accord pronominal invalide");
            t=inverted.group(1)+" "+inverted.group(2)+" "+inverted.group(3)+" est "+inverted.group(5);
        }
        Matcher outer=SUBJECT.matcher(t);
        if(!outer.matches()) throw new IllegalArgumentException("Phrase relationnelle non interprétable");
        String article=outer.group(1),kind=outer.group(2),id=outer.group(3),tail=outer.group(4);
        checkArticle(article,kind);
        ensureEntity(kind,id);
        for(Entry e:entries) {
            if(kind.equals(e.subjectKind())) {
                for(boolean negative:List.of(false,true)) {
                    List<String> leads=new ArrayList<>();
                    String verb=e.activeVerb();
                    if(negative) {
                        leads.add("ne "+verb+" pas ");
                        leads.add("n'"+verb+" pas ");
                    } else {
                        leads.add(verb+" ");
                        leads.add(verb+(kind.equals("porte")?"-elle ":"-il "));
                        leads.add(verb+"-t-il ");
                    }
                    for(String lead:leads) {
                        if(!tail.startsWith(lead))continue;
                        if((lead.contains("-il ")||lead.contains("-elle ")) && !question)
                            throw new IllegalArgumentException("Inversion hors interrogation");
                        var object=entity(tail.substring(lead.length()));
                        if(!object.kind().equals(e.objectKind())) continue;
                        var frame=new SemanticIntermediateRepresentation.RelationFrame(e.concept(),
                            new SemanticIntermediateRepresentation.EntityRef(kind,id),object,negative);
                        return new Analysis(frame,formula(frame),question?ControlledFrench.Act.ASK:ControlledFrench.Act.ASSERT);
                    }
                }
            }
            if(kind.equals(e.objectKind())) {
                for(boolean negative:List.of(false,true)) {
                    String participle=e.passiveVerb().substring(4);
                    List<String> leads=negative ? List.of("n'est pas "+participle+" ",
                            "n'est-"+(kind.equals("porte")?"elle":"il")+" pas "+participle+" ")
                            :List.of(e.passiveVerb()+" ",
                            "est-"+(kind.equals("porte")?"elle":"il")+" "+participle+" ");
                    for(String lead:leads) {
                        if(!tail.startsWith(lead))continue;
                        if((lead.startsWith("est-")||lead.startsWith("n'est-"))&&!question)
                            throw new IllegalArgumentException("Inversion passive hors interrogation");
                        var source=entity(tail.substring(lead.length()));
                        if(!source.kind().equals(e.subjectKind()))continue;
                        var frame=new SemanticIntermediateRepresentation.RelationFrame(e.concept(),source,
                            new SemanticIntermediateRepresentation.EntityRef(kind,id),negative);
                        return new Analysis(frame,formula(frame),question?ControlledFrench.Act.ASK:ControlledFrench.Act.ASSERT);
                    }
                }
            }
        }
        throw new IllegalArgumentException("Relation, rôles, accords ou négation hors grammaire ; aucun sens inventé");
    }
    private SemanticIntermediateRepresentation.EntityRef entity(String text) {
        Matcher m=ENTITY.matcher(text);
        if(!m.matches()) throw new IllegalArgumentException("Objet relationnel mal formé");
        checkArticle(m.group(1),m.group(2));ensureEntity(m.group(2),m.group(3));
        return new SemanticIntermediateRepresentation.EntityRef(m.group(2),m.group(3));
    }
    private void ensureEntity(String kind,String name) {
        if(!language.entities().contains(new ControlledFrench.Entity(kind,name)))
            throw new IllegalArgumentException("Entité non déclarée : "+kind+" "+name);
    }
    public static Formula formula(SemanticIntermediateRepresentation.RelationFrame frame) {
        Formula atom=Formula.atom("relation_"+frame.relation()+"_"+frame.subject().kind()+"_"
                +frame.subject().name()+"_"+frame.object().kind()+"_"+frame.object().name());
        return frame.negated()?Formula.not(atom):atom;
    }
    private static void checkArticle(String article,String kind) {
        if(!article.equals(kind.equals("porte")?"la":"le"))
            throw new IllegalArgumentException("Genre et article incompatibles");
    }
    private static String normal(String text) {
        return Objects.requireNonNull(text).strip().toLowerCase(Locale.ROOT)
            .replace('’','\'').replaceAll("\\s+"," ");
    }
}
