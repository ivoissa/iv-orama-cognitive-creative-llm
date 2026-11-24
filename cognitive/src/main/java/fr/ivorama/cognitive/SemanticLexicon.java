package fr.ivorama.cognitive;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * IVOC-LING-002 : représentation lexicale explicite des propriétés.
 * Une entrée relie une forme de surface à un concept, sous contraintes
 * d'accord grammatical et de catégories autorisées. Aucun fait n'est induit
 * d'une entrée lexicale. Les homonymies ne sont jamais résolues arbitrairement.
 */
public final class SemanticLexicon {
    public enum Number { SINGULAR, PLURAL }
    public enum Gender { MASCULINE, FEMININE }

    /** Sens lexical et paradigme adjectival, sans assertion sur le monde. */
    public record Sense(String concept, Set<String> allowedKinds,
                        String masculineSingular, String feminineSingular,
                        String masculinePlural, String femininePlural) {
        public Sense {
            Objects.requireNonNull(concept);
            if(!concept.matches("[a-z][a-z0-9_]*"))
                throw new IllegalArgumentException("Identifiant sémantique invalide : "+concept);
            allowedKinds=Set.copyOf(Objects.requireNonNull(allowedKinds));
            if(allowedKinds.isEmpty() || !KINDS.keySet().containsAll(allowedKinds))
                throw new IllegalArgumentException("Catégories lexicales non autorisées");
            masculineSingular=word(masculineSingular);
            feminineSingular=word(feminineSingular);
            masculinePlural=word(masculinePlural);
            femininePlural=word(femininePlural);
        }
        public String form(Gender gender, Number number) {
            return number==Number.SINGULAR
                    ? (gender==Gender.FEMININE?feminineSingular:masculineSingular)
                    : (gender==Gender.FEMININE?femininePlural:masculinePlural);
        }
        public boolean matches(String kind, String surface, Number number) {
            return allowedKinds.contains(kind) && form(gender(kind),number).equals(word(surface));
        }
    }

    public static final class AmbiguousMeaningException extends GrammarValidationException {
        private final List<String> candidates;
        private final String surface;
        public String surface() { return surface; }
        public AmbiguousMeaningException(String expression, Collection<String> candidates) {
            super("Clarification lexicale nécessaire pour '"+expression+"' : sens possibles "
                    +String.join(", ",candidates));
            this.candidates=List.copyOf(candidates);
            this.surface=word(expression);
        }
        public List<String> candidates() { return candidates; }
    }
    private static final Map<String,Gender> KINDS=Map.of("document",Gender.MASCULINE,
            "dossier",Gender.MASCULINE,"objet",Gender.MASCULINE,"porte",Gender.FEMININE);
    private final List<Sense> entries=new ArrayList<>();
    private long version;
    public synchronized long version() { return version; }

    public SemanticLexicon() {
        register("valide",KINDS.keySet(),"valide","valide","valides","valides");
        register("approuve",KINDS.keySet(),"validé","validée","validés","validées");
        register("approuve",KINDS.keySet(),"approuvé","approuvée","approuvés","approuvées");
        register("archive",KINDS.keySet(),"archivé","archivée","archivés","archivées");
        register("ouvert",KINDS.keySet(),"ouvert","ouverte","ouverts","ouvertes");
        register("ferme",KINDS.keySet(),"fermé","fermée","fermés","fermées");
        register("actif",KINDS.keySet(),"actif","active","actifs","actives");
        register("disponible",KINDS.keySet(),"disponible","disponible","disponibles","disponibles");
        register("complet",KINDS.keySet(),"complet","complète","complets","complètes");
    }
    public static Gender gender(String kind) {
        Gender value=KINDS.get(kind);
        if(value==null) throw new IllegalArgumentException("Catégorie nominale inconnue : "+kind);
        return value;
    }
    private static String conceptId(String token) {
        String s=Objects.requireNonNull(token).strip().toLowerCase(Locale.ROOT);
        if(!s.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("Identifiant de sens invalide : "+token);
        return s;
    }
    private static String word(String token) {
        String s=Objects.requireNonNull(token).strip().toLowerCase(Locale.ROOT);
        if(!s.matches("[\\p{L}]+"))
            throw new IllegalArgumentException("Lexème invalide : "+token);
        return s;
    }

    /** Apprendre un sens lexical n'est PAS apprendre une proposition vraie. */
    public synchronized void register(String concept, Set<String> kinds,
                    String masculineSingular, String feminineSingular,
                    String masculinePlural, String femininePlural) {
        addSense(new Sense(concept,kinds,masculineSingular,feminineSingular,
                masculinePlural,femininePlural), false);
    }
    /** La polysémie est une décision explicite, jamais un effet caché de register. */
    public synchronized void registerPolysemy(String concept, Set<String> kinds,
                    String masculineSingular, String feminineSingular,
                    String masculinePlural, String femininePlural) {
        addSense(new Sense(concept,kinds,masculineSingular,feminineSingular,
                masculinePlural,femininePlural), true);
    }
    private void addSense(Sense next, boolean allowPolysemy) {
        if(entries.contains(next)) return;
        if(!allowPolysemy) for(Sense old:entries) {
            if(old.concept().equals(next.concept())) continue;
            for(String kind:next.allowedKinds()) {
                if(!old.allowedKinds().contains(kind)) continue;
                for(Number number:Number.values()) {
                    String a=old.form(gender(kind),number);
                    String b=next.form(gender(kind),number);
                    if(a.equals(b)) throw new IllegalArgumentException(
                        "Collision de sens sur '"+b+"' pour "+kind
                        +" ("+old.concept()+" / "+next.concept()
                        +"). Utiliser registerPolysemy pour une ambiguïté intentionnelle.");
                }
            }
        }
        entries.add(next); version++;
    }

    /** Empreinte du CONTENU lexical, stable entre sessions et indépendante du compteur de version. */
    public synchronized String fingerprint() { return sha256(payload()); }
    private String payload() {
        StringBuilder b=new StringBuilder("IVOC-LEXICON-1\n");
        for(Sense sense:entries) {
            b.append(sense.concept()).append('\t')
             .append(String.join(",",new TreeSet<>(sense.allowedKinds()))).append('\t')
             .append(sense.masculineSingular()).append('\t')
             .append(sense.feminineSingular()).append('\t')
             .append(sense.masculinePlural()).append('\t')
             .append(sense.femininePlural()).append('\n');
        }
        return b.toString();
    }
    private static String sha256(String text) {
        try {
            byte[] data=MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(data);
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 absent",e); }
    }
    /** Sauvegarde lisible et auto-contrôlée ; empreinte d'intégrité, pas signature de confiance. */
    public synchronized void save(Path target) throws IOException {
        Objects.requireNonNull(target);
        String data=payload();
        Path absolute=target.toAbsolutePath();
        Path parent=absolute.getParent();
        if(parent==null || !Files.isDirectory(parent))
            throw new IOException("Le répertoire du lexique doit exister");
        Path temp=Files.createTempFile(parent,".ivoc-lex-", ".tmp");
        try {
            Files.writeString(temp,data+"SHA256\t"+sha256(data)+"\n",StandardCharsets.UTF_8);
            try { Files.move(temp,absolute,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException unsupported) {
                Files.move(temp,absolute,StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }
    /** Charge une copie certifiée intègre, sans changer le lexique actif en cas d'erreur. */
    public synchronized void load(Path target) throws IOException {
        Objects.requireNonNull(target);
        String data=Files.readString(target,StandardCharsets.UTF_8);
        int index=data.lastIndexOf("SHA256\t");
        if(index<0 || !data.endsWith("\n")) throw new IOException("Format du lexique invalide");
        String body=data.substring(0,index);
        String hash=data.substring(index+7).trim();
        if(!hash.matches("[0-9a-f]{64}") || !sha256(body).equals(hash))
            throw new IOException("Empreinte lexicale incohérente");
        String[] lines=body.split("\n",-1);
        if(lines.length<2 || !lines[0].equals("IVOC-LEXICON-1") || !lines[lines.length-1].isEmpty())
            throw new IOException("Version ou format lexical inconnu");
        SemanticLexicon parsed=new SemanticLexicon();
        parsed.entries.clear(); parsed.version=0;
        try {
            for(int i=1;i<lines.length-1;i++) {
                String[] fields=lines[i].split("\t",-1);
                if(fields.length!=6) throw new IllegalArgumentException("Ligne lexicale invalide");
                Set<String> kinds=new HashSet<>(Arrays.asList(fields[1].split(",")));
                parsed.addSense(new Sense(fields[0],kinds,fields[2],fields[3],fields[4],fields[5]),true);
                if(parsed.entries.size()!=i) throw new IllegalArgumentException("Doublon lexical");
            }
            if(!parsed.payload().equals(body)) throw new IllegalArgumentException("Ordre ou normalisation invalide");
        } catch(IllegalArgumentException ex) { throw new IOException("Données lexicales invalides : "+ex.getMessage(),ex); }
        entries.clear(); entries.addAll(parsed.entries);
        version++;
    }
    public synchronized List<Sense> entries() { return List.copyOf(entries); }

    public synchronized String resolve(String surface, String kind, Number number) {
        gender(kind);
        String w=word(surface);
        Set<String> meanings=new TreeSet<>();
        boolean surfaceExists=false;
        for(Sense e:entries) {
            if(e.masculineSingular().equals(w) || e.feminineSingular().equals(w)
                    || e.masculinePlural().equals(w) || e.femininePlural().equals(w)) surfaceExists=true;
            if(e.matches(kind,w,number)) meanings.add(e.concept());
        }
        if(meanings.isEmpty()) throw new GrammarValidationException(surfaceExists
                ? "Accord ou catégorie incompatible : "+kind+" / "+surface
                : "Mot absent du lexique : "+surface);
        if(meanings.size()>1) throw new AmbiguousMeaningException(surface,meanings);
        return meanings.iterator().next();
    }
    /** Choix explicite LOCAL de l'utilisateur ; ne change jamais le lexique ni ses faits. */
    public synchronized String resolveSelected(String surface, String kind, Number number, String selected) {
        String chosen=conceptId(selected);
        Set<String> possibilities=new TreeSet<>();
        for(Sense sense:entries) if(sense.matches(kind,word(surface),number))
            possibilities.add(sense.concept());
        if(!possibilities.contains(chosen))
            throw new GrammarValidationException("Sens non admissible pour '"+surface+"' : "+chosen);
        return chosen;
    }
    /** Génération par lemme ou forme connue, suivie d'une validation en sens inverse. */
    public synchronized String surfaceFor(String meaningOrForm, String kind, Number number) {
        Gender gender=gender(kind);
        String target=Objects.requireNonNull(meaningOrForm).strip().toLowerCase(Locale.ROOT);
        if(!target.matches("[\\p{L}]+") && !target.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("Mot ou concept invalide : "+meaningOrForm);
        Set<String> meanings=new TreeSet<>();
        for(Sense s:entries) if(s.allowedKinds().contains(kind)
                && (s.concept().equals(target) || s.masculineSingular().equals(target)
                || s.feminineSingular().equals(target) || s.masculinePlural().equals(target)
                || s.femininePlural().equals(target))) meanings.add(s.concept());
        if(meanings.isEmpty()) throw new IllegalArgumentException("Mot ou sens absent du lexique : "+target);
        if(meanings.size()>1) throw new AmbiguousMeaningException(target,meanings);
        String concept=meanings.iterator().next();
        for(Sense s:entries) if(s.concept().equals(concept) && s.allowedKinds().contains(kind)) {
            String form=s.form(gender,number);
            // Éviter de générer une phrase devenue ambiguë après extension du lexique.
            try { if(resolve(form,kind,number).equals(concept)) return form; }
            catch(AmbiguousMeaningException ambiguous) { /* essayer une forme synonyme */ }
        }
        throw new IllegalArgumentException("Aucune forme générable sans ambiguïté pour : "+target);
    }
}
