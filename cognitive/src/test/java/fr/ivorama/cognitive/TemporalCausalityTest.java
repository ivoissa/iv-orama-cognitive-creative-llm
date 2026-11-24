package fr.ivorama.cognitive;

import java.util.*;
import fr.ivorama.cognitive.SemanticIntermediateRepresentation.*;

/** IVOC-FND-007 : audit de vues historiques, causation et FCI, distinct d'EVAL-001. */
public final class TemporalCausalityTest {
    private static int n;
    private static void check(boolean b,String msg) { n++; if(!b) throw new AssertionError(msg); }
    private static void fail(Runnable r,String msg) {
        boolean thrown=false;
        try { r.run(); } catch(IllegalArgumentException ex) { thrown=true; }
        check(thrown,msg);
    }
    public static int runAll() {
        n=0;
        var d=new FrenchQuestionDialogue();
        var base=d.cycle().knowledgeVersion();
        d.declareTemporalEvent("evenement E1 validation dossier A temps 1 connu 8 source paul");
        var v1=d.temporalLedger().version();
        check(v1==1,"version initiale");
        // L'evenement a eu lieu a t1, mais son enregistrement peut survenir bien plus tard.
        var q=d.ask("La validation E1 du dossier A est-elle active à t5 selon journal v1 ?");
        check(q.questionId().equals("Q1") && !q.pending(),"Q1 historique ferme conditionnellement");
        check(q.text().contains("historique figée"),"vue historique explicitement annoncee");
        check(d.cycle().temporalQuestion("Q1").asOfJournalVersion().orElseThrow()==1,"RSI instantanee associee a Q1");
        d.declareTemporalEvent("evenement E2 annulation E1 dossier A temps 4 connu 9 source paul");
        var q2=d.ask("La validation E1 du dossier A est-elle active à t5 ?");
        check(q2.text().contains("annulée"),"vue actuelle tient compte de l'annulation");
        check(d.status("Q1").text().contains("active selon"),"vue ancienne ne change pas");
        check(!d.status("Q1").text().contains("a été annulée"),"pas de fuite des donnees futures");
        d.retractTemporalEvent("E2","erreur de saisie");
        check(!d.temporalLedger().active("E2"),"identifiant E2 majuscule normalise lors du retrait");
        check(d.status(q2.questionId()).text().contains("active selon"),"retrait rend la validation active");
        check(d.temporalLedger().assessAtVersion(new TemporalQuestionFrame(
                new EntityRef("dossier","A"), QuestionKind.VALIDATION_ACTIVE,5,Optional.of("e1")),2)
                .finding()==TemporalEventLedger.Finding.INACTIVE_CONDITIONALLY,"v2 garde l'annulation retiree plus tard");
        var tq=new TemporalQuestionFrame(new EntityRef("dossier","a"),QuestionKind.VALIDATION_ACTIVE,5,Optional.of("e1"));
        check(d.temporalLedger().assessDeclaredKnownBy(tq,2,7).finding()==TemporalEventLedger.Finding.NOT_ESTABLISHED,
                "validation pas encore declaree connue a t7");
        check(d.temporalLedger().assessDeclaredKnownBy(tq,2,8).finding()==TemporalEventLedger.Finding.ACTIVE_CONDITIONALLY,
                "validation connue selon source t8, annulation pas avant t9");
        check(d.temporalLedger().assessDeclaredKnownBy(tq,2,9).finding()==TemporalEventLedger.Finding.INACTIVE_CONDITIONALLY,
                "annulation declaree connue a t9");
        check(d.temporalLedger().assessDeclaredKnownBy(tq,2,9).explanation().contains("non une preuve"),
                "pas de confusion temps declare et connaissance reelle");
        fail(()->d.temporalLedger().assessDeclaredKnownBy(tq,2,-1),"borne negative refusee");
        check(d.temporalLedger().journal().size()==3,"journal append-only");
        check(d.cycle().knowledgeVersion()==base,"ne modifie pas la logique propositionnelle");
        check(d.cycle().questionActCount()==2,"seuls les actes interrogatifs creent FCI");
        var before=d.ask("Le dossier A est-il archivé à t10 selon journal v3 ?");
        check(before.pending(),"l'archivage n'est pas implique par validation");
        d.declareTemporalEvent("evenement E3 archivage dossier A temps 6 connu 9 source alice");
        check(d.status(before.questionId()).pending(),"vue historique v3 invariante a l'ajout e3");
        var now=d.ask("Le dossier A est-il archivé à t10 ?");
        check(!now.pending(),"archivage declare visible actuellement");
        var causal=d.ask("L'événement E1 a-t-il causé E3 ?");
        check(causal.pending() && causal.text().contains("Indéterminé"),"anteriorite seule ne prouve rien");
        check(d.cycle().questionAct(causal.questionId()).state()==IntegratedQuestionCycle.ActState.TEMPORAL,
                "causalite conserve RSI et obligation FCI");
        fail(()->d.declareCausalClaim("cause C1 E3 E1 connu 10 source alice"),"chronologie inverse interdite");
        fail(()->d.declareCausalClaim("cause C1 E1 E99 connu 10 source alice"),"effet inconnu interdit");
        check(d.temporalLedger().version()==4,"rejet d'une hypothese ne modifie pas le journal");
        d.declareCausalClaim("cause C1 E1 E3 connu 10 source alice");
        var claimed=d.status(causal.questionId());
        check(claimed.pending(),"hypothese ne clot pas FCI causale");
        check(claimed.text().contains("DECLAR"),"hypothese etiquetee clairement");
        check(claimed.text().contains("[e1, e3, c1]"),"provenance causale contient declaration et evenements");
        var historical=d.ask("L'événement E1 a-t-il causé E3 selon journal v4 ?");
        check(historical.pending() && historical.text().contains("Indéterminé"),
                "la declaration de causalite en v5 n'existait pas en v4");
        var historicalClaim=d.ask("L'événement E1 a-t-il causé E3 selon journal v5 ?");
        check(historicalClaim.pending() && historicalClaim.text().contains("DECLAR"),
                "v5 conserve la declaration sans la verifier");
        d.retractCausalClaim("C1","correction");
        check(!d.temporalLedger().active("C1"),"identifiant C1 majuscule normalise lors du retrait");
        check(d.status(causal.questionId()).pending(),"retraction causale laisse ouverte la question");
        check(d.status(historicalClaim.questionId()).text().contains("DECLAR"),
                "vue historique de la causalite preservee");
        d.retractTemporalEvent("e3","evenement non confirme");
        check(d.status(now.questionId()).pending(),"archivage courant reouvert");
        check(d.status(historicalClaim.questionId()).text().contains("DECLAR"),
                "historique avant retraction conserve ses supports");
        fail(()->d.temporalLedger().assessAtVersion(new CausalQuestionFrame("e1","e3"),999),
                "future vue interdite");
        var beyond=d.ask("La validation E1 du dossier A est-elle active à t5 selon journal v999 ?");
        check(beyond.text().contains("NON INTERPRÉTÉE") && beyond.pending(),
                "vue future sans formule inventee ; Q est conservee");
        // La grammaire controlee reste volontairement restrictive.
        var unknown=d.ask("Les evenements E1 et E3 sont-ils lies causalement, peut-etre ?");
        check(unknown.text().contains("NON INTERPRÉTÉE"),"pas de sens invente pour la question libre");
        check(d.cycle().knowledgeVersion()==base,"aucun changement de premisses par requete");
        check(d.cycle().journal().stream().anyMatch(e->e.operation().equals("QUESTION_REOPENED")),
                "reouverture FCI enregistree");
        // Rejeu historique elementaire : toute annulation suivie de retrait a une vue stable.
        for(int i=0;i<25;i++) {
            var ledger=new TemporalEventLedger();
            String id="e"+(100+i), c="e"+(200+i);
            var ent=new EntityRef("dossier","d"+i);
            ledger.admit(new EventFrame(id,ent,EventKind.VALIDATION,1,2,"test",Optional.empty()));
            ledger.admit(new EventFrame(c,ent,EventKind.ANNULATION_VALIDATION,3,4,"test",Optional.of(id)));
            ledger.retract(c,"correctif");
            var question=new TemporalQuestionFrame(ent,QuestionKind.VALIDATION_ACTIVE,5,Optional.of(id));
            check(ledger.assessAtVersion(question,1).finding()==TemporalEventLedger.Finding.ACTIVE_CONDITIONALLY,
                    "vue v1 invariante "+i);
            check(ledger.assessAtVersion(question,2).finding()==TemporalEventLedger.Finding.INACTIVE_CONDITIONALLY,
                    "vue v2 invariante "+i);
            check(ledger.assessAtVersion(question,3).finding()==TemporalEventLedger.Finding.ACTIVE_CONDITIONALLY,
                    "vue v3 invariante "+i);
        }
        return n;
    }
    public static void main(String[] args) { System.out.println("TEMPORAL-CAUSAL TEST OK - "+runAll()+" assertions"); }
}
