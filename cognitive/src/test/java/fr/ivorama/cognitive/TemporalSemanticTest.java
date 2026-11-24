package fr.ivorama.cognitive;

import java.util.*;
import fr.ivorama.cognitive.SemanticIntermediateRepresentation.*;

/** Contrats RSI/FCI/temporels testés indépendamment du banc aveugle EVAL-001. */
public final class TemporalSemanticTest {
    private static int assertions;
    private static void check(boolean condition,String message) {
        assertions++;
        if(!condition) throw new AssertionError(message);
    }
    private static void expectFailure(Runnable operation,String message) {
        boolean failed=false;
        try { operation.run(); }
        catch(IllegalArgumentException expected) { failed=true; }
        check(failed,message);
    }
    public static int runAll() {
        assertions=0;
        var d=new FrenchQuestionDialogue();
        var knowledgeBefore=d.cycle().knowledgeVersion();
        check(d.cycle().questionActCount()==0,"univers avant question stable");
        check(d.temporalLedger().version()==0,"registre d'événements initialement vide");
        d.declareTemporalEvent("evenement E1 validation dossier A temps 1 connu 2 source paul");
        check(d.temporalLedger().version()==1,"premier événement déclaré");
        check(d.cycle().knowledgeVersion()==knowledgeBefore,"événement non introduit comme prémisse logique");
        check(d.cycle().questionActCount()==0,"déclarer événement ne crée pas FCI");
        var before=d.ask("La validation E1 du dossier A est-elle active à t0 ?");
        check(before.questionId().equals("Q1"),"FCI Q1 temporelle");
        check(before.pending(),"avant événement inconnu plutôt que faux");
        check(d.status("Q1").text().contains("Indéterminé"),"pas de validation rétroactive");
        var q=d.ask("La validation E1 du dossier A est-elle active à t3 ?");
        check(q.questionId().equals("Q2"),"FCI Q2, pas une interprétation de Q1");
        check(!q.pending(),"validation en cours soutenue");
        check(q.text().contains("active selon"),"réponse conditionnelle");
        check(d.cycle().questionAct("Q2").state()==IntegratedQuestionCycle.ActState.TEMPORAL,"registre typé");
        check(d.cycle().questionAct("Q2").formula().isEmpty(),"pas de formule propositionnelle inventée");
        check(d.cycle().temporalQuestion("Q2").initialEventVersion()==1,"photographie version prise à Q");
        check(d.cycle().questionActCount()==2,"une faille par question");
        var archive=d.ask("Le dossier A est-il archivé à t3 ?");
        check(archive.pending(),"valide ne prouve pas archivé");
        check(archive.text().contains("Indéterminé"),"monde ouvert archivé");
        d.declareTemporalEvent("evenement E2 annulation E1 dossier A temps 4 connu 7 source paul");
        check(d.status("Q2").text().contains("active selon"),"annulation ultérieure n'affecte pas t3");
        var after=d.ask("La validation E1 du dossier A est-elle active à t5 ?");
        check(after.text().contains("a été annulée"),"annulation à t4 met fin à cette validation à t5");
        check(d.cycle().temporalQuestion(after.questionId()).initialEventVersion()==2,"version à l'instant Q");
        check(d.status("Q2").text().contains("version 2"),"rapport recalculé mais question identique");
        check(d.cycle().questionActCount()==4,"4 demandes dont absence d'archive");
        var ledger=d.temporalLedger();
        var snapshot=ledger.assess(new TemporalQuestionFrame(new EntityRef("dossier","a"),
                QuestionKind.VALIDATION_ACTIVE,5,Optional.of("E1")));
        check(snapshot.finding()==TemporalEventLedger.Finding.INACTIVE_CONDITIONALLY,"évaluation typée de l'annulation");
        check(snapshot.supports().equals(List.of("e1","e2")),"supports causaux déclarés explicites");
        d.retractTemporalEvent("e2","information rétractée");
        check(d.status(after.questionId()).text().contains("active selon"),"réouverture/recalcul après retrait");
        check(ledger.journal().stream().anyMatch(x->x.action().equals("RETRAIT")),"journal non effacé");
        d.declareTemporalEvent("evenement E3 archivage dossier A temps 2 connu 9 source alice");
        check(!d.status(archive.questionId()).pending(),"archivage explicite peut déterminer Q3");
        check(d.status(archive.questionId()).text().contains("réalité n'est pas vérifiée"),"pas d'auto-certification");
        check(d.status(after.questionId()).text().contains("active selon"),"archivage indépendant de validation");
        d.retractTemporalEvent("e3","correction");
        check(d.status(archive.questionId()).pending(),"archive réouverte après retrait");
        check(d.cycle().journal().stream().anyMatch(x->x.questionId().equals(archive.questionId())
                && x.operation().equals("QUESTION_REOPENED")),"FCI journalise la réouverture temporelle");
        expectFailure(()->d.declareTemporalEvent("evenement E4 annulation E404 dossier A temps 6 connu 9 source alice"),
                "annulation orpheline refusée");
        expectFailure(()->d.declareTemporalEvent("evenement E4 validation dossier A temps 5 connu 4 source alice"),
                "déclaration antérieure à l'événement refusée");
        expectFailure(()->d.declareTemporalEvent("evenement E1 validation dossier A temps 10 connu 11 source alice"),
                "ID immuable");
        expectFailure(()->d.declareTemporalEvent("evenement E5 annulation E1 dossier B temps 6 connu 8 source alice"),
                "annulation d'un autre dossier refusée");
        check(d.cycle().knowledgeVersion()==knowledgeBefore,"pas de mutation de la base propositionnelle");
        d.retractTemporalEvent("e1","validation déclarée invalidée");
        check(d.status(after.questionId()).pending(),"sans source de validation, obligation rouverte");
        check(d.cycle().journal().stream().anyMatch(x->x.questionId().equals(after.questionId())
                && x.operation().equals("QUESTION_REOPENED")),"réouverture FCI après révocation de validation");
        check(d.cycle().questionActCount()==4,"aucun acte Q supplémentaire par révision");
        check(d.withdraw("Q2").text().contains("retirée"),"retrait explicite Q temporelle");
        check(d.cycle().questionAct("Q2").state()==IntegratedQuestionCycle.ActState.WITHDRAWN,"retrait conservé au journal");
        check(d.cycle().questionActCount()==4,"nombre d'actes Q invariant au retrait");
        var uninterpreted=d.ask("Le dossier A est-il archivé depuis toujours ?");
        check(uninterpreted.text().contains("NON INTERPRÉTÉE"),"temps libre hors grammaire non inventé");
        check(d.cycle().questionActCount()==5,"FCI même quand l'analyse échoue");
        return assertions;
    }
    public static void main(String[] args) { System.out.println("TEMPORAL TEST OK - "+runAll()+" assertions"); }
}
