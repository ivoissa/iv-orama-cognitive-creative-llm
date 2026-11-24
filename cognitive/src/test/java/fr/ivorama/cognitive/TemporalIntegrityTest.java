package fr.ivorama.cognitive;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import fr.ivorama.cognitive.SemanticIntermediateRepresentation.*;

/** IVOC-FND-008 : tests of real persistence, audit semantics and open-world guard. */
public final class TemporalIntegrityTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if(!condition) throw new AssertionError(message);
    }
    private static void mustFailIO(IOAction runnable,String message) {
        boolean failed=false;
        try { runnable.run(); } catch(IOException e) { failed=true; }
        check(failed,message);
    }
    @FunctionalInterface private interface IOAction { void run() throws IOException; }
    private static EventFrame frame(String id,EventKind kind,long event,long known,String target) {
        return new EventFrame(id,new EntityRef("dossier","a"),kind,event,known,"alice",
                target==null?Optional.empty():Optional.of(target));
    }
    public static int runAll() throws Exception {
        assertions=0;
        TemporalEventLedger ledger=new TemporalEventLedger();
        var tq=new TemporalQuestionFrame(new EntityRef("dossier","a"),
                QuestionKind.VALIDATION_ACTIVE,5,Optional.of("e1"));
        var aq=new TemporalQuestionFrame(new EntityRef("dossier","a"),
                QuestionKind.ARCHIVAGE_CONSTATE,5,Optional.empty());
        check(ledger.assessAtVersion(tq,0).finding()==TemporalEventLedger.Finding.NOT_ESTABLISHED,
                "silence ne vaut pas FALSE");
        check(ledger.assessAtVersion(aq,0).finding()==TemporalEventLedger.Finding.NOT_ESTABLISHED,
                "absence archivage n'est pas preuve d'absence");
        check(ledger.verifyJournalIntegrity() && ledger.headHash().length()==64,
                "genesis verifiable");
        ledger.admit(frame("e1",EventKind.VALIDATION,1,8,null));
        String h1=ledger.headHash();
        check(ledger.version()==1 && ledger.verifyJournalIntegrity(),"premier maillon valide");
        // Backdated source knowledge remains invisible to a prior ledger version.
        ledger.admit(frame("e2",EventKind.ARCHIVAGE,2,3,null));
        check(ledger.assess(aq,TemporalEventLedger.TemporalView.atVersion(1)).finding()
                ==TemporalEventLedger.Finding.NOT_ESTABLISHED,"aucune fuite vers v1");
        check(ledger.assess(aq,TemporalEventLedger.TemporalView.atVersionWithSourceCutoff(1,4)).finding()
                ==TemporalEventLedger.Finding.NOT_ESTABLISHED,"filtre ne retroagit pas sur version");
        check(ledger.assess(aq,TemporalEventLedger.TemporalView.atVersionWithSourceCutoff(2,4)).finding()
                ==TemporalEventLedger.Finding.ARCHIVED_CONDITIONALLY,"source declaree visible en v2");
        check(ledger.assess(aq,TemporalEventLedger.TemporalView.atVersionWithSourceCutoff(2,1)).finding()
                ==TemporalEventLedger.Finding.NOT_ESTABLISHED,"date alleguee appliquee independamment");
        check(!h1.equals(ledger.headHash()),"hash des entrees chaines");
        ledger.admit(frame("e3",EventKind.ANNULATION_VALIDATION,4,9,"e1"));
        ledger.admitCausalClaim(new CausalClaim("c1","e1","e2",11,"alice"));
        var causal=new CausalQuestionFrame("e1","e2");
        check(ledger.assess(causal,TemporalEventLedger.TemporalView.atVersion(4)).finding()
                ==TemporalEventLedger.Finding.CAUSAL_CLAIM_ONLY,"hypothese non preuve");
        check(!ledger.assess(causal,TemporalEventLedger.TemporalView.atVersion(4)).determined(),
                "FCI causale reste ouverte");
        check(TemporalEventLedger.CAUSAL_FRAGMENT.equals("STRICT_TEMPORAL_CAUSALITY_FRAGMENT"),
                "hypothese est bornee au fragment temporel strict");
        var v2Before=ledger.assessAtVersion(aq,2);
        ledger.retract("E2","rectification externe");
        ledger.retractCausalClaim("C1","erreur de causalite");
        check(ledger.verifyJournalIntegrity(),"retrait conserve chaine valide");
        check(ledger.assessAtVersion(aq,2).equals(v2Before),"vue historique intacte");
        check(ledger.assess(aq,TemporalEventLedger.TemporalView.atVersion(ledger.version())).finding()
                ==TemporalEventLedger.Finding.NOT_ESTABLISHED,"retrait rouvre constat archivage");
        check(ledger.auditTrail().get(0).previousHash().equals("0".repeat(64)),"genesis audit");
        check(ledger.auditTrail().get(1).previousHash().equals(h1),"hash precedent lie");
        check(ledger.auditTrail().size()==ledger.journal().size(),"aucune entree oubliée");
        Path dir=Files.createTempDirectory("ivoc-fnd-008-test-");
        try {
            Path saved=dir.resolve("journal.ivoc");
            Path saved2=dir.resolve("journal2.ivoc");
            ledger.saveSnapshot(saved);
            ledger.saveSnapshot(saved2);
            check(Arrays.equals(Files.readAllBytes(saved),Files.readAllBytes(saved2)),
                    "snapshot binaire deterministe");
            TemporalEventLedger replay=TemporalEventLedger.loadSnapshot(saved);
            check(replay.verifyJournalIntegrity(),"snapshot rejoue verifie");
            check(replay.headHash().equals(ledger.headHash()),"hash final identique");
            check(replay.auditTrail().equals(ledger.auditTrail()),"operations et contenus canoniques identiques");
            check(replay.journal().equals(ledger.journal()),"journal public reconstitue");
            check(replay.declaredEvents().equals(ledger.declaredEvents()),"RSI reconstituees");
            check(replay.declaredCausalClaims().equals(ledger.declaredCausalClaims()),"hypotheses reconstituees");
            for(long v=0;v<=ledger.version();v++) {
                check(replay.assessAtVersion(aq,v).equals(ledger.assessAtVersion(aq,v)),
                        "vue archivage reproductible "+v);
                check(replay.assessAtVersion(tq,v).equals(ledger.assessAtVersion(tq,v)),
                        "vue validation reproductible "+v);
                check(replay.assessAtVersion(causal,v).equals(ledger.assessAtVersion(causal,v)),
                        "vue causale reproductible "+v);
            }
            byte[] original=Files.readAllBytes(saved);
            byte[] corrupted=original.clone();
            corrupted[corrupted.length/2] ^= 1;
            Path tampered=dir.resolve("tampered.ivoc");
            Files.write(tampered,corrupted);
            mustFailIO(()->TemporalEventLedger.loadSnapshot(tampered),
                    "modification binaire detectee");
            Path truncated=dir.resolve("truncated.ivoc");
            Files.write(truncated,Arrays.copyOf(original,original.length-5));
            mustFailIO(()->TemporalEventLedger.loadSnapshot(truncated),"troncature detectee");
            Path extended=dir.resolve("extended.ivoc");
            Files.write(extended,Arrays.copyOf(original,original.length+2));
            mustFailIO(()->TemporalEventLedger.loadSnapshot(extended),"suffixe non journalise refuse");
            long before=ledger.version();
            try { ledger.retract("e2","again"); throw new AssertionError("retrait accepte deux fois"); }
            catch(IllegalArgumentException expected) { check(ledger.version()==before,"erreur sans inscription"); }
        } finally {
            try(var list=Files.list(dir)) { list.forEach(path->{try{Files.deleteIfExists(path);}catch(IOException e){throw new RuntimeException(e);}}); }
            Files.delete(dir);
        }
        // Integrity covers dates, author labels and the content, not only identifiers.
        var lSource=new TemporalEventLedger();
        var lTime=new TemporalEventLedger();
        lSource.admit(frame("e1",EventKind.VALIDATION,1,8,null));
        lTime.admit(new EventFrame("e1",new EntityRef("dossier","a"),
                EventKind.VALIDATION,1,8,"bob",Optional.empty()));
        check(!lSource.headHash().equals(lTime.headHash()),"source incluse dans hash");
        var lKnown=new TemporalEventLedger();
        lKnown.admit(frame("e1",EventKind.VALIDATION,1,9,null));
        check(!lSource.headHash().equals(lKnown.headHash()),"knownTime inclut dans hash");
        var lEventTime=new TemporalEventLedger();
        lEventTime.admit(frame("e1",EventKind.VALIDATION,2,8,null));
        check(!lSource.headHash().equals(lEventTime.headHash()),"eventTime inclut dans hash");
        var oversized=new TemporalEventLedger();
        try {
            oversized.admit(new EventFrame("e1",new EntityRef("dossier","a"),
                    EventKind.VALIDATION,1,2,"x".repeat(500_000),Optional.empty()));
            throw new AssertionError("Ecriture non sauvegardable admise");
        } catch(IllegalArgumentException expected) {
            check(oversized.version()==0 && oversized.journal().isEmpty(),
                    "rejet atomique du payload volumineux");
        }
        // Deterministic fixed-seed metaproperty: multiple independent histories retain old views.
        Random random=new Random(20261013);
        for(int i=0;i<40;i++) {
            var l=new TemporalEventLedger();
            var e=new EntityRef("dossier","x"+i);
            String start="e"+(100+i), cancel="e"+(300+i);
            long t0=1+random.nextInt(20);
            l.admit(new EventFrame(start,e,EventKind.VALIDATION,t0,t0+2,"test",Optional.empty()));
            var q=new TemporalQuestionFrame(e,QuestionKind.VALIDATION_ACTIVE,t0+5,Optional.of(start));
            var first=l.assessAtVersion(q,1);
            l.admit(new EventFrame(cancel,e,EventKind.ANNULATION_VALIDATION,t0+3,t0+4,
                    "test",Optional.of(start)));
            l.retract(cancel,"undo");
            check(l.assessAtVersion(q,1).equals(first),"vue sans fuite "+i);
            check(l.assessAtVersion(q,2).finding()==TemporalEventLedger.Finding.INACTIVE_CONDITIONALLY,
                    "vue annulation "+i);
            check(l.assessAtVersion(q,3).finding()==TemporalEventLedger.Finding.ACTIVE_CONDITIONALLY,
                    "vue retour "+i);
            check(l.verifyJournalIntegrity(),"chaine coherente "+i);
        }
        // Concurrency: public ledger operations are serialized, versions stay unique.
        var concurrent=new TemporalEventLedger();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int worker=0;worker<6;worker++) {
                final int w=worker;
                futures.add(pool.submit(()->{
                    for(int j=0;j<20;j++) {
                        concurrent.admit(new EventFrame("e"+(8000+w*20+j),
                                new EntityRef("dossier","a"),EventKind.ARCHIVAGE,
                                1,1,"worker"+w,Optional.empty()));
                    }
                }));
            }
            for(var f:futures) f.get();
            check(concurrent.version()==120,"versions synchronisees entre threads");
            check(concurrent.verifyJournalIntegrity(),"chaine coherente avec append concurrent");
            check(concurrent.declaredEvents().size()==120,"aucune operation perdue");
        } finally { pool.shutdownNow(); }
        return assertions;
    }
    public static void main(String[] args) throws Exception {
        System.out.println("TEMPORAL-INTEGRITY TEST OK - "+runAll()+" assertions");
    }
}
