package fr.ivorama.cognitive;

import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;

/** Régressions revues Claude L1–L4, L9 ; distinctes du benchmark 100 inédit. */
public final class ReviewLing003FixTest {
    private static int checks;
    private static void require(boolean ok, String why) {
        checks++;
        if(!ok) throw new AssertionError("LING-003-REVIEW: "+why);
    }
    private static void rejected(Runnable run,String why) {
        boolean bad=false;
        try {run.run();} catch(IllegalArgumentException | IllegalStateException ex) {bad=true;}
        require(bad,why);
    }
    private static FrenchQuestionDialogue setup() {
        var d=new FrenchQuestionDialogue();
        d.declare("document","A"); d.declare("document","B");
        d.registerAmbiguousAdjective("ouvert_juridique",Set.of("document"),"ouvert","ouverte","ouverts","ouvertes");
        return d;
    }
    public static int runAll() {
        checks=0;
        // L1 : un sens à '_' et chiffres doit être sélectionnable.
        var d=setup();
        var q=d.ask("Le document A est-il ouvert ?");
        require(q.pending() && q.text().contains("ouvert_juridique"),"sens candidat affiché");
        var answer=d.clarify(q.questionId(),"ouvert_juridique");
        require(!answer.pending(),"concept avec underscore sélectionné");
        require(answer.report().question().equals(Formula.atom("ouvert_juridique_document_a")),"bonne formule");
        require(d.lexicalFingerprint().matches("[0-9a-f]{64}"),"empreinte intacte");
        var lex=d.language().lexicon();
        rejected(()->lex.surfaceFor("ouvert_juridique","document",SemanticLexicon.Number.SINGULAR),
                "aucune génération de surface ambiguë sans choix explicite");
        // L2 : instantané des prémisses PRIS au Ask, jamais au Clarify.
        var f=setup();
        var pending=f.ask("Le document A est-il ouvert ?");
        require(f.cycle().unresolvedQuestions().get(0).initialPremises().isEmpty(),"snapshot initial vide");
        f.teach("Le document A est disponible.");
        require(f.cycle().unresolvedQuestions().get(0).initialPremises().isEmpty(),
                "l'ajout entre question et clarification ne change pas la base initiale");
        f.clarify(pending.questionId(),"ouvert_juridique");
        require(f.cycle().question(pending.questionId()).questionGainBits().isPresent(),
                "gain question relatif à l'instant de l'acte");
        require(f.cycle().unresolvedQuestions().isEmpty(),"question résolue sans deuxième acte Q");
        // L2 avec une preuve pertinente ajoutée après Q, via API logique, sans traduction ambiguë.
        var g=setup();
        var open=g.ask("Le document A est-il ouvert ?");
        g.cycle().teach(Formula.atom("ouvert_juridique_document_a"),"test conditionnel");
        var solved=g.clarify(open.questionId(),"ouvert_juridique");
        require(solved.report().answer().equals(Optional.of(true)),"prémisse postérieure détermine réponse");
        require(solved.report().questionGainBits().isPresent()
                && solved.report().questionGainBits().getAsDouble()>0.5,
                "gain mesuré depuis l'acte interrogatif initial, pas depuis la clarification");
        // L3 : deux occurrences identiques peuvent choisir deux sens différents.
        var h=setup();
        var both=h.ask("Le document A est ouvert et le document B est ouvert ?");
        require(both.pending(),"question composée ambiguë admise");
        var half=h.clarify(both.questionId(),"ouvert_juridique");
        require(half.pending(),"deuxième occurrence nécessite un choix indépendant");
        var full=h.clarify(both.questionId(),"ouvert");
        require(!full.pending() && full.questionId().equals(both.questionId()),"même Q après deux choix");
        require(full.report().question().equals(Formula.and(
                Formula.atom("ouvert_juridique_document_a"),Formula.atom("ouvert_document_b"))),
                "les deux occurrences ne fusionnent pas");
        require(h.cycle().journal().stream().filter(e->e.operation().equals("QUESTION_POSED")).count()==1,
                "un seul acte Q");
        // L4 : un changement de lexique bloque la clarification d'un ancien contexte.
        var k=setup();
        var obsolete=k.ask("Le document A est-il ouvert ?");
        String originalFingerprint=k.lexicalFingerprint();
        k.registerAdjective("neuf",Set.of("document"),"neuf","neuve","neufs","neuves");
        require(!k.lexicalFingerprint().equals(originalFingerprint),"empreinte changée");
        rejected(()->k.clarify(obsolete.questionId(),"ouvert_juridique"),
                "ancien candidat non confirmé sous lexique modifié");
        require(k.status(obsolete.questionId()).pending(),"obligation gardée malgré refus");
        var withdrawn=k.withdraw(obsolete.questionId());
        require(!withdrawn.pending() && withdrawn.reportOptional().isEmpty(),"retrait terminal sans rapport logique fictif");
        require(k.cycle().withdrawnQuestions().size()==1,"retrait conservé en audit");
        require(k.cycle().questionActCount()==1,"historique d'acte Q conservé");
        require(k.cycle().journal().stream().anyMatch(e->e.operation().equals("CLARIFICATION_REFUSED")),
                "retrait journalisé");
        rejected(()->k.clarify(obsolete.questionId(),"ouvert_juridique"),"retrait terminal");
        require(k.status(obsolete.questionId()).text().contains("retirée"),"statut consultable après retrait");
        // L9 : budget de chaque composante indépendant des autres, pas liste globale trompeuse.
        List<Formula> independent=new ArrayList<>();
        for(int i=0;i<1100;i++) independent.add(Formula.atom("x"+i));
        var result=ModelExplorer.analyze(independent,Formula.atom("x0"),ModelExplorer.Budget.small());
        require(result.status()==ModelExplorer.Status.PROVEN_TRUE,"1100 composantes indépendantes admises");
        require(!result.completeWorlds() && result.possibleWorlds().isEmpty(),
                "pas de fausse énumération complète");
        // L9 strict : chaque composante HORS 2-SAT a 3 atomes (8 valuations),
        // l'addition globale des 1100 composants dépasse 4096, mais pas chaque composante.
        List<Formula> nonTwoSatComponents=new ArrayList<>();
        for(int i=0;i<1100;i++) {
            var a=Formula.atom("l"+i+"a");var b=Formula.atom("l"+i+"b");
            var c=Formula.atom("l"+i+"c");
            nonTwoSatComponents.add(Formula.implies(Formula.and(a,b),c));
        }
        var local=ModelExplorer.analyze(nonTwoSatComponents,Formula.atom("l0a"),
                ModelExplorer.Budget.small());
        require(local.status()==ModelExplorer.Status.UNDETERMINED,
                "1100 composantes non-2SAT sous budget local, sans clôture fausse");
        require(!local.completeWorlds() && local.possibleWorlds().isEmpty(),
                "composantes locales distinctes de la liste de mondes globale");
        var inconsistentComponents=new ArrayList<>(nonTwoSatComponents);
        inconsistentComponents.add(Formula.atom("l1099a"));
        inconsistentComponents.add(Formula.atom("l1099b"));
        inconsistentComponents.add(Formula.not(Formula.atom("l1099c")));
        require(ModelExplorer.analyze(inconsistentComponents,Formula.atom("l0a"),
                ModelExplorer.Budget.small()).status()==ModelExplorer.Status.INCONSISTENT,
                "contradiction éloignée ne peut être ignorée par la factorisation");
        var linked=new ArrayList<Formula>();
        for(int i=0;i<13;i++) linked.add(Formula.implies(Formula.atom("y"+i),Formula.atom("y"+(i+1))));
        require(ModelExplorer.analyze(linked,Formula.atom("y13"),ModelExplorer.Budget.small()).status()
                  ==ModelExplorer.Status.UNDETERMINED,"chaîne 2-SAT reconnue sans prémisse initiale");
        require(ModelExplorer.analyze(linked,Formula.atom("y13"),new ModelExplorer.Budget(3,8)).status()
                  ==ModelExplorer.Status.RESOURCE_LIMIT,"budget strict reste strict");
        // La révision du lexique ne doit pas modifier les faits existants.
        require(k.cycle().premises().isEmpty(),"révisions lexicales ne créent pas de prémisses");
        return checks;
    }
    public static void main(String[] args) {
        System.out.println("REVIEW LING-003 OK - "+runAll()+" vérifications");
    }
}
