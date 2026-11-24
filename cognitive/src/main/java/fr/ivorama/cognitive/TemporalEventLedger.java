package fr.ivorama.cognitive;

import java.util.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import fr.ivorama.cognitive.SemanticIntermediateRepresentation.*;

/**
 * Registre declaratif a versions immuables pour l'audit, pas verite empirique.
 * La version du journal est un ordre d'inscription/retrait, pas une date physique.
 * L'etat du monde (eventTime) et l'information rapportee (knownTime) sont distincts.
 */
public final class TemporalEventLedger {
    public enum Finding { ACTIVE_CONDITIONALLY, INACTIVE_CONDITIONALLY,
                          ARCHIVED_CONDITIONALLY, CAUSAL_CLAIM_ONLY, NOT_ESTABLISHED }
    public record Assessment(Finding finding, List<String> supports, long ledgerVersion,
                             String explanation) {
        public Assessment { supports=List.copyOf(supports); }
        public boolean determined() {
            return finding!=Finding.NOT_ESTABLISHED && finding!=Finding.CAUSAL_CLAIM_ONLY;
        }
    }
    public record JournalEntry(long version, String action, String id, String detail) {}
    /** A hash-linked record over the FULL, canonicalized operation payload. No identity attestation. */
    public record AuditEntry(long version, String action, String id, String detail,
                             String payload, String previousHash, String hash) {}
    /** Explicit semantic distinction: a system ledger snapshot versus a source-ALLEGED date. */
    public record TemporalView(long journalVersion, OptionalLong declaredKnownTimeCutoff) {
        public TemporalView { Objects.requireNonNull(declaredKnownTimeCutoff); }
        public static TemporalView atVersion(long version) {
            return new TemporalView(version, OptionalLong.empty());
        }
        public static TemporalView atVersionWithSourceCutoff(long version, long cutoff) {
            if (cutoff < 0) throw new IllegalArgumentException("Date declaree negative");
            return new TemporalView(version, OptionalLong.of(cutoff));
        }
    }
    /** Strictly scoped fragment: chronological compatibility, NEVER causal proof. */
    public static final String CAUSAL_FRAGMENT = "STRICT_TEMPORAL_CAUSALITY_FRAGMENT";
    private static final String MAGIC = "IVOC-FND-008-JOURNAL-V1";
    private static final String GENESIS = "0".repeat(64);
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_PAYLOAD = 512_000;
    private final List<AuditEntry> audit = new ArrayList<>();
    private final Map<String,EventFrame> events=new LinkedHashMap<>();
    private final Map<String,CausalClaim> causalClaims=new LinkedHashMap<>();
    private final Map<String,Long> born=new HashMap<>();
    private final Map<String,Long> ended=new HashMap<>();
    private final List<JournalEntry> history=new ArrayList<>();
    private long version;

    public synchronized long version() { return version; }
    public synchronized List<JournalEntry> journal() { return List.copyOf(history); }
    public synchronized List<AuditEntry> auditTrail() { return List.copyOf(audit); }
    public synchronized String headHash() { return audit.isEmpty()?GENESIS:audit.get(audit.size()-1).hash(); }
    public synchronized boolean verifyJournalIntegrity() {
        if (version != history.size() || version != audit.size()) return false;
        String prior = GENESIS;
        for (int i=0; i<audit.size(); i++) {
            AuditEntry item = audit.get(i);
            JournalEntry plain = history.get(i);
            if (item.version()!=i+1L || !plain.equals(new JournalEntry(item.version(),
                    item.action(),item.id(),item.detail())) || !prior.equals(item.previousHash())) return false;
            if (!item.hash().equals(hash(item.version(), item.action(),item.id(),
                    item.detail(),item.payload(),prior))) return false;
            prior=item.hash();
        }
        return true;
    }
    public synchronized List<EventFrame> declaredEvents() { return List.copyOf(events.values()); }
    public synchronized List<CausalClaim> declaredCausalClaims() { return List.copyOf(causalClaims.values()); }
    public synchronized boolean active(String id) { return activeAt(id,version); }
    public synchronized boolean activeAt(String id,long viewVersion) {
        checkVersion(viewVersion);
        id=Objects.requireNonNull(id).strip().toLowerCase(Locale.ROOT);
        Long start=born.get(id);
        return start!=null && start<=viewVersion &&
                (!ended.containsKey(id) || ended.get(id)>viewVersion);
    }
    private void checkVersion(long viewVersion) {
        if(viewVersion<0 || viewVersion>version)
            throw new IllegalArgumentException("Version du journal future ou negative");
    }
    private void write(String action,String id,String detail,String payload) {
        long next=version+1;
        String previous=headHash();
        String digest=hash(next,action,id,detail,payload,previous);
        JournalEntry plain=new JournalEntry(next,action,id,detail);
        AuditEntry secured=new AuditEntry(next,action,id,detail,payload,previous,digest);
        // Construct all immutable audit objects before publishing the new version.
        history.add(plain);
        audit.add(secured);
        version=next;
    }
    private static String hash(long version, String action, String id, String detail,
                               String payload, String previous) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(bytes("IVOC-FND-008-HASH-V1"));
            digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(version).array());
            for (String part : List.of(action,id,detail,payload,previous)) digest.update(bytes(part));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static byte[] bytes(String value) {
        byte[] data=value.getBytes(StandardCharsets.UTF_8);
        byte[] result=java.nio.ByteBuffer.allocate(4+data.length).putInt(data.length).put(data).array();
        return result;
    }
    private static String payload(String... strings) {
        try {
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            DataOutputStream out=new DataOutputStream(buffer);
            out.writeInt(strings.length);
            for (String s: strings) {
                byte[] data=s.getBytes(StandardCharsets.UTF_8);
                out.writeInt(data.length); out.write(data);
            }
            out.flush();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.toByteArray());
        } catch(IOException impossible) { throw new AssertionError(impossible); }
    }
    private static String[] unpack(String encoded) throws IOException {
        byte[] bytes;
        try { bytes=Base64.getUrlDecoder().decode(encoded); }
        catch(IllegalArgumentException e) { throw new IOException("Payload invalide",e); }
        if(bytes.length>MAX_PAYLOAD) throw new IOException("Payload trop grand");
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))) {
            int n=in.readInt();
            if(n<0 || n>16) throw new IOException("Nombre de champs invalide");
            String[] parts=new String[n];
            for(int i=0;i<n;i++) {
                int length=in.readInt();
                if(length<0 || length>MAX_PAYLOAD) throw new IOException("Champ invalide");
                parts[i]=new String(in.readNBytes(length),StandardCharsets.UTF_8);
                if(parts[i].getBytes(StandardCharsets.UTF_8).length!=length) throw new IOException("Troncature");
            }
            if(in.available()!=0) throw new IOException("Octets supplementaires");
            return parts;
        }
    }
    private static String boundedPayload(String value) {
        if(value.length()>MAX_PAYLOAD || value.getBytes(StandardCharsets.UTF_8).length>MAX_PAYLOAD)
            throw new IllegalArgumentException("Contenu du journal trop volumineux");
        return value;
    }
    private static String eventPayload(EventFrame e) {
        return payload(e.id(),e.entity().kind(),e.entity().name(),e.kind().name(),
                Long.toString(e.eventTime()),Long.toString(e.knownTime()),e.source(),
                e.targetId().orElse(""));
    }
    private static String claimPayload(CausalClaim c) {
        return payload(c.id(),c.causeEventId(),c.effectEventId(),
                Long.toString(c.declaredKnownTime()),c.source());
    }
    public synchronized void admit(EventFrame event) {
        Objects.requireNonNull(event);
        if(born.containsKey(event.id())) throw new IllegalArgumentException("Identifiant deja employe");
        if(event.kind()==EventKind.ANNULATION_VALIDATION) {
            EventFrame target=events.get(event.targetId().orElseThrow());
            if(target==null || !active(target.id()) || target.kind()!=EventKind.VALIDATION
                    || !target.entity().equals(event.entity()) || event.eventTime()<=target.eventTime())
                throw new IllegalArgumentException("Annulation sans validation anterieure active du meme objet");
        }
        String data=boundedPayload(eventPayload(event));
        events.put(event.id(),event);
        write("DECLARATION",event.id(),event.kind()+" ; source declaree="+event.source(),data);
        born.put(event.id(),version);
    }
    /** Une relation cause-effet declaree est une hypothese, meme avec provenance. */
    public synchronized void admitCausalClaim(CausalClaim claim) {
        Objects.requireNonNull(claim);
        if(born.containsKey(claim.id())) throw new IllegalArgumentException("ID causal deja employe");
        EventFrame a=events.get(claim.causeEventId());
        EventFrame b=events.get(claim.effectEventId());
        if(a==null || b==null || !active(a.id()) || !active(b.id())
                || a.eventTime()>=b.eventTime() || claim.declaredKnownTime()<b.eventTime())
            throw new IllegalArgumentException("Hypothese sans deux evenements actifs ordonnes");
        String data=boundedPayload(claimPayload(claim));
        causalClaims.put(claim.id(),claim);
        write("HYPOTHESE_CAUSALE",claim.id(),
                claim.causeEventId()+" -> "+claim.effectEventId()+" ; declaration de "+claim.source(),
                data);
        born.put(claim.id(),version);
    }
    public synchronized void retract(String eventId,String cause) {
        eventId=Objects.requireNonNull(eventId).strip().toLowerCase(Locale.ROOT);
        if(!events.containsKey(eventId) || !active(eventId))
            throw new IllegalArgumentException("Evenement inconnu ou deja retire");
        end(eventId,cause,"EVENT");
    }
    public synchronized void retractCausalClaim(String id,String cause) {
        id=Objects.requireNonNull(id).strip().toLowerCase(Locale.ROOT);
        if(!causalClaims.containsKey(id) || !active(id))
            throw new IllegalArgumentException("Hypothese inconnue ou deja retiree");
        end(id,cause,"CAUSE");
    }
    private void end(String id,String cause,String kind) {
        if(cause==null || cause.isBlank()) throw new IllegalArgumentException("Cause de retrait requise");
        String data=boundedPayload(payload(kind,cause.strip()));
        write("RETRAIT",id,cause.strip(),data);
        ended.put(id,version);
    }
    public synchronized Assessment assess(TemporalQuestionFrame q) { return assessAtVersion(q,version); }
    public synchronized Assessment assessAtVersion(TemporalQuestionFrame q,long viewVersion) {
        return assessAtVersion((SemanticIntermediateRepresentation)q,viewVersion);
    }
    public synchronized Assessment assessAtVersion(SemanticIntermediateRepresentation q,long viewVersion) {
        return assessInView(q,viewVersion,OptionalLong.empty());
    }
    public synchronized Assessment assess(SemanticIntermediateRepresentation q,TemporalView view) {
        Objects.requireNonNull(view);
        return assessInView(q,view.journalVersion(),view.declaredKnownTimeCutoff());
    }
    /**
     * Deux contraintes : journal reel a la version v et date de connaissance
     * ALLEGUEE par la source <= cutoff. Ne pas appeler cela connaissance historique
     * effective du systeme : knownTime est saisi par l'utilisateur, pas horodate par lui.
     */
    public synchronized Assessment assessDeclaredKnownBy(SemanticIntermediateRepresentation q,
                                             long viewVersion,long knownCutoff) {
        if(knownCutoff<0) throw new IllegalArgumentException("Borne de connaissance negative");
        Assessment a=assess(q,TemporalView.atVersionWithSourceCutoff(viewVersion,knownCutoff));
        return new Assessment(a.finding(),a.supports(),a.ledgerVersion(),a.explanation()
                +" ; filtre de connaissance DECLAREE t<="+knownCutoff
                +" (non une preuve de ce que le systeme savait alors)");
    }
    private boolean visible(EventFrame e,long viewVersion,OptionalLong knownCutoff) {
        return activeAt(e.id(),viewVersion)
                && (knownCutoff.isEmpty() || e.knownTime()<=knownCutoff.getAsLong());
    }
    private Assessment assessInView(SemanticIntermediateRepresentation q,long viewVersion,
                                    OptionalLong knownCutoff) {
        Objects.requireNonNull(q);
        checkVersion(viewVersion);
        if(q instanceof CausalQuestionFrame causal) return causalAssessment(causal,viewVersion,knownCutoff);
        if(!(q instanceof TemporalQuestionFrame query))
            throw new IllegalArgumentException("RSI de question attendue");
        if(query.kind()==QuestionKind.ARCHIVAGE_CONSTATE) {
            for(EventFrame e:events.values()) {
                if(visible(e,viewVersion,knownCutoff) && e.kind()==EventKind.ARCHIVAGE
                        && e.entity().equals(query.entity()) && e.eventTime()<=query.atTime())
                    return new Assessment(Finding.ARCHIVED_CONDITIONALLY,List.of(e.id()),viewVersion,
                            "Archivage DECLARE a t"+e.eventTime()+" par "+e.source()+" ; pas de preuve empirique");
            }
            return unknown(viewVersion,"Aucun archivage declare dans cette vue ; absence dans le monde non prouvee");
        }
        String targetId=query.validationId().orElseThrow();
        EventFrame validation=events.get(targetId);
        if(validation==null || !visible(validation,viewVersion,knownCutoff) || validation.kind()!=EventKind.VALIDATION
                || !validation.entity().equals(query.entity()) || validation.eventTime()>query.atTime())
            return unknown(viewVersion,"Validation ciblee absente, retiree ou posterieure dans cette vue");
        EventFrame ending=null;
        for(EventFrame e:events.values()) {
            if(visible(e,viewVersion,knownCutoff) && e.kind()==EventKind.ANNULATION_VALIDATION
                    && e.targetId().orElseThrow().equals(targetId) && e.eventTime()<=query.atTime()
                    && (ending==null || e.eventTime()<ending.eventTime())) ending=e;
        }
        if(ending!=null) return new Assessment(Finding.INACTIVE_CONDITIONALLY,
                List.of(targetId,ending.id()),viewVersion,
                "Validation declaree a t"+validation.eventTime()+" puis annulee a t"+ending.eventTime()
                +" ; les autres validations et l'archivage ne sont pas determines");
        return new Assessment(Finding.ACTIVE_CONDITIONALLY,List.of(targetId),viewVersion,
                "Validation ciblee active dans le REGISTRE selon les declarations et leur exhaustivite supposee ; "
                +"absence d'annulation non prouvee dans le monde");
    }
    private Assessment causalAssessment(CausalQuestionFrame q,long viewVersion,OptionalLong knownCutoff) {
        EventFrame cause=events.get(q.causeEventId()), effect=events.get(q.effectEventId());
        if(cause==null || effect==null || !visible(cause,viewVersion,knownCutoff)
                || !visible(effect,viewVersion,knownCutoff))
            return unknown(viewVersion,"Evenement cause ou effet indisponible dans cette vue");
        for(CausalClaim c:causalClaims.values()) {
            if(activeAt(c.id(),viewVersion)
                    && (knownCutoff.isEmpty() || c.declaredKnownTime()<=knownCutoff.getAsLong())
                    && c.causeEventId().equals(q.causeEventId())
                    && c.effectEventId().equals(q.effectEventId()))
                return new Assessment(Finding.CAUSAL_CLAIM_ONLY,
                        List.of(cause.id(),effect.id(),c.id()),viewVersion,
                        "Hypothese cause-effet DECLAREE par "+c.source()
                        +" ; non verifiee ; l'obligation de causalite reste ouverte");
        }
        return unknown(viewVersion,"La succession temporelle ne demontre aucune causalite");
    }
    private Assessment unknown(long viewVersion,String reason) {
        return new Assessment(Finding.NOT_ESTABLISHED,List.of(),viewVersion,reason);
    }
    /**
     * Manual checkpoint, NOT automatic write-ahead durability. Atomic rename is required:
     * failure to support it returns an exception instead of silently claiming atomicity.
     * The digest is not a signature, secure time stamp, or independent witness.
     */
    public synchronized void saveSnapshot(Path destination) throws IOException {
        Objects.requireNonNull(destination);
        if(!verifyJournalIntegrity()) throw new IOException("Journal incoherent");
        Path absolute=destination.toAbsolutePath();
        Path directory=absolute.getParent();
        Files.createDirectories(directory);
        Path staging=Files.createTempFile(directory,"ivoc-ledger-",".tmp");
        boolean moved=false;
        try {
            try(FileOutputStream file=new FileOutputStream(staging.toFile());
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(file))) {
                writeString(out,MAGIC);
                out.writeInt(audit.size());
                for(AuditEntry item:audit) {
                    out.writeLong(item.version());
                    writeString(out,item.action()); writeString(out,item.id());
                    writeString(out,item.detail()); writeString(out,item.payload());
                    writeString(out,item.previousHash()); writeString(out,item.hash());
                }
                out.flush();
                // Buffered stream must be flushed before forcing bytes.
                file.getFD().sync();
            }
            Files.move(staging,absolute,StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            moved=true;
        } finally {
            if(!moved) Files.deleteIfExists(staging);
        }
    }
    public static TemporalEventLedger loadSnapshot(Path source) throws IOException {
        Objects.requireNonNull(source);
        if(Files.size(source)>100_000_000L) throw new IOException("Snapshot trop grand");
        TemporalEventLedger restored=new TemporalEventLedger();
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
            if(!MAGIC.equals(readString(in))) throw new IOException("Format de journal inconnu");
            int count=in.readInt();
            if(count<0 || count>MAX_ENTRIES) throw new IOException("Nombre d'entrees invalide");
            for(int i=0;i<count;i++) {
                AuditEntry item=new AuditEntry(in.readLong(), readString(in),readString(in),
                        readString(in),readString(in),readString(in),readString(in));
                if(item.version()!=i+1L || !restored.headHash().equals(item.previousHash())
                        || !item.hash().equals(hash(item.version(),item.action(),item.id(),
                        item.detail(),item.payload(),item.previousHash())))
                    throw new IOException("Chaine d'integrite invalide a la version "+(i+1));
                restoreEntry(restored,item);
                if(!item.equals(restored.audit.get(i)))
                    throw new IOException("Donnees d'evenement non conformes a la chaine");
            }
            if(in.read()!=-1) throw new IOException("Octets non documentes apres le journal");
        } catch(IllegalArgumentException | IllegalStateException ex) {
            throw new IOException("Journal invalide ou impossible a rejouer",ex);
        }
        if(!restored.verifyJournalIntegrity()) throw new IOException("Replay incomplet");
        return restored;
    }
    private static void restoreEntry(TemporalEventLedger ledger,AuditEntry entry) throws IOException {
        String[] parts=unpack(entry.payload());
        try {
            switch(entry.action()) {
                case "DECLARATION" -> {
                    if(parts.length!=8) throw new IOException("Evenement mal forme");
                    ledger.admit(new EventFrame(parts[0],new EntityRef(parts[1],parts[2]),
                            EventKind.valueOf(parts[3]),Long.parseLong(parts[4]),
                            Long.parseLong(parts[5]),parts[6],
                            parts[7].isEmpty()?Optional.empty():Optional.of(parts[7])));
                }
                case "HYPOTHESE_CAUSALE" -> {
                    if(parts.length!=5) throw new IOException("Hypothese mal formee");
                    ledger.admitCausalClaim(new CausalClaim(parts[0],parts[1],parts[2],
                            Long.parseLong(parts[3]),parts[4]));
                }
                case "RETRAIT" -> {
                    if(parts.length!=2) throw new IOException("Retrait mal forme");
                    if("EVENT".equals(parts[0])) ledger.retract(entry.id(),parts[1]);
                    else if("CAUSE".equals(parts[0])) ledger.retractCausalClaim(entry.id(),parts[1]);
                    else throw new IOException("Type de retrait inconnu");
                }
                default -> throw new IOException("Operation inconnue dans le journal");
            }
        } catch(IllegalArgumentException ex) {
            throw new IOException("Rejeu semantiquement invalide",ex);
        }
    }
    private static void writeString(DataOutputStream out,String text) throws IOException {
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        if(data.length>MAX_PAYLOAD) throw new IOException("Chaine trop longue");
        out.writeInt(data.length); out.write(data);
    }
    private static String readString(DataInputStream in) throws IOException {
        int n=in.readInt();
        if(n<0 || n>MAX_PAYLOAD) throw new IOException("Longueur invalide");
        byte[] data=in.readNBytes(n);
        if(data.length!=n) throw new IOException("Fichier tronque");
        return new String(data,StandardCharsets.UTF_8);
    }

}
