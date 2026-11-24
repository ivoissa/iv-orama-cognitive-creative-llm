package fr.ivorama.cognitive;

import java.util.*;

/** Contrôles anti-régression des sept échecs DEV-100. Ne remplace pas un examen aveugle. */
public final class Regression068Test {
    private static int n;
    private static void ok(boolean b,String what) { n++; if(!b)throw new AssertionError("068: "+what); }
    private static void reject(Runnable r,String what) {
        boolean failed=false;
        try { r.run(); } catch(IllegalArgumentException|IllegalStateException ex) { failed=true; }
        ok(failed,what);
    }
    public static int runAll() {
        n=0;
        // DED-10 : l'élision doit fonctionner pour objet, sans autoriser 'l'porte'.
        var a=new FrenchQuestionDialogue();a.declare("objet","a");
        a.teach("L'objet a est approuvé.");
        var yes=a.ask("L'objet a est-il validé ?");
        ok(yes.report().answer().equals(Optional.of(true)),"élision dans affirmation et question");
        ok(yes.report().question().equals(Formula.atom("approuve_objet_a")),"un seul concept partagé");
        ok(a.cycle().questionActCount()==1,"une seule question objet");
        var invalid=new FrenchQuestionDialogue();invalid.declare("porte","a");
        reject(()->invalid.teach("L'porte a est ouverte."),"élision grammaticalement fausse rejetée");
        // DED-13 : parse des deux membres SANS transformer p -> (q & r) en (p -> q) & r.
        var d=new FrenchQuestionDialogue();d.declare("porte","a");
        d.teach("Si la porte a est disponible, alors la porte a est ouverte et la porte a est active.");
        var q=d.ask("La porte a est-elle active ?");
        ok(q.report().status()==IntegratedQuestionCycle.Status.OPEN,"l'implication ne prouve pas son conséquent sans antécédent");
        d.teach("La porte a est disponible.");
        ok(d.status(q.questionId()).report().answer().equals(Optional.of(true)),"modus ponens puis projection de conjonction");
        var core=new ControlledFrench();core.declareEntity("porte","a");
        reject(()->CompositionalFrench.analyse(core,"La porte a est ouverte et actif."),
            "coordination elliptique mal accordée/non complète rejetée");
        reject(()->d.teach("Si la porte a est disponible, alors la porte a est active et rouge."),
            "conjonction incomplète de clauses non déduite arbitrairement");
        // INC-12 / DIA-16 / TRA-12 : variantes stylistiques de la même vue historique.
        var t=new FrenchQuestionDialogue();t.declare("dossier","b");
        t.declareTemporalEvent("evenement E1 archivage dossier B temps 3 connu 10 source paul");
        var v0=t.ask("Le dossier B est-il archivé à t5 selon le journal v0 ?");
        ok(t.cycle().questionAct(v0.questionId()).state()==IntegratedQuestionCycle.ActState.TEMPORAL,
            "expression temporelle analysée");
        var v0short=t.ask("Le dossier B est-il archivé à t5 selon journal v0 ?");
        ok(t.cycle().temporalQuestion(v0.questionId()).asOfJournalVersion().orElseThrow()==0L &&
           t.cycle().temporalQuestion(v0short.questionId()).asOfJournalVersion().orElseThrow()==0L,
           "deux graphies = version 0");
        ok(t.temporalLedger().assessAtVersion(t.cycle().temporalQuestion(v0.questionId()).frame(),0L)
             .finding()==TemporalEventLedger.Finding.NOT_ESTABLISHED,"absence dans vue 0 reste indéterminée");
        var present=t.ask("Le dossier B est-il archivé à t5 ?");
        ok(t.cycle().temporalQuestion(present.questionId()).asOfJournalVersion().isEmpty(),
            "vue courante distincte de vue historique");
        ok(t.cycle().questionActCount()==3,"chaque interrogation temporelle a sa FCI");
        // DIA-18 : la surface ambiguë n'est pas une obligation de génération univoque.
        var s=new FrenchQuestionDialogue();s.declare("document","a");s.declare("document","b");
        s.registerAmbiguousAdjective("u1",Set.of("document"),"validé","validée","validés","validées");
        s.registerAmbiguousAdjective("u2",Set.of("document"),"validé","validée","validés","validées");
        var first=s.ask("Est-ce que tous les documents ne sont pas validés ?");
        ok(first.pending(),"polysémie nécessite clarification");
        var second=s.clarify(first.questionId(),"u1");
        ok(second.pending() && second.text().contains("pas_tous"),"choix u1 suivi d'une clarification de portée");
        var last=s.clarify(first.questionId(),"pas_tous");
        ok(last.questionId().equals(first.questionId()) && s.cycle().questionActCount()==1,
            "la double clarification reste une seule question");
        ok(last.report().question().equals(Formula.not(Formula.and(Formula.atom("u1_document_a"),
                    Formula.atom("u1_document_b")))),"formule choisie correcte");
        // TRA-19 : choix par occurrence transmis au parseur mixte relation/propriété.
        var m=new FrenchQuestionDialogue();m.declare("document","a");m.declare("dossier","b");
        m.registerAdjective("w1",Set.of("document"),"rare","rare","rares","rares");
        m.registerAmbiguousAdjective("w2",Set.of("document"),"rare","rare","rares","rares");
        m.teach("Le dossier b est contenu dans le document a.");
        var mq=m.ask("Est-ce que le document a est rare et le document a contient le dossier b ?");
        ok(mq.pending(),"ambiguïté lexicale dans une conjonction mixte");
        var resolved=m.clarify(mq.questionId(),"w1");
        ok(!resolved.pending(),"la clarification résout la phrase mixte");
        ok(resolved.report().question().equals(Formula.and(Formula.atom("w1_document_a"),
                  Formula.atom("relation_contient_document_a_dossier_b"))),"sens composé préservé");
        ok(resolved.report().status()==IntegratedQuestionCycle.Status.OPEN,
           "relation connue et propriété inconnue ne suffit pas à clore");
        ok(m.cycle().questionActCount()==1,"pas de Q supplémentaire durant le choix lexical");
        return n;
    }
    public static void main(String[] args) {System.out.println("REGRESSION-068 OK - "+runAll()+" contrôles");}
}
