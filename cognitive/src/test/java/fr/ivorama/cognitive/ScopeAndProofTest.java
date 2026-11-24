package fr.ivorama.cognitive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** IVOC-LING-004: développement, NON benchmark EVAL-001 indépendant. */
public final class ScopeAndProofTest {
    private static int n;
    private static void ok(boolean condition, String message) {
        n++;
        if(!condition) throw new AssertionError("LING-004: "+message);
    }
    private static void rejects(Runnable r, String message) {
        boolean rejected=false;
        try {r.run();} catch(IllegalArgumentException | IllegalStateException ex) {rejected=true;}
        ok(rejected,message);
    }
    private static FrenchQuestionDialogue docs() {
        var dialogue=new FrenchQuestionDialogue();
        dialogue.declare("document","A");
        dialogue.declare("document","B");
        return dialogue;
    }
    private static String scopeQ() {return "Est-ce que tous les documents ne sont pas validés ?";}
    public static int runAll() {
        n=0;
        var d=docs();
        int before=d.cycle().questionActCount();
        int premBefore=d.cycle().premises().size();
        var question=d.ask(scopeQ());
        ok(question.pending(),"Q ambiguë doit rester en attente");
        ok(question.reportOptional().isEmpty(),"pas de rapport inventé sur une lecture non décidée");
        ok(d.cycle().questionActCount()==before+1,"un seul acte interrogatif");
        ok(question.text().contains("pas_tous") && question.text().contains("aucun"),"deux lectures nommées");
        ok(question.text().contains("Pas tous") && question.text().contains("Aucun"),"gloses humaines");
        ok(d.cycle().premises().size()==premBefore,"aucune prémisse créée par Q");
        rejects(()->d.clarify(question.questionId(),"au_pif"),"lecture étrangère refusée");
        ok(d.status(question.questionId()).pending(),"choix erroné ne perd pas l'obligation");
        // La lecture est fixée au moment de Q même si le domaine déclaré s'étend après.
        d.declare("document","C");
        d.teach("Le document A n'est pas validé.");
        d.teach("Le document B est validé.");
        var decided=d.clarify(question.questionId(),"pas_tous");
        ok(decided.questionId().equals(question.questionId()),"même Q après clarification");
        ok(decided.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "pas tous est vrai si A n'est pas validé");
        ok(decided.report().answer().equals(Optional.of(true)),"valeur vraie de pas_tous");
        ok(decided.report().question().equals(Formula.not(Formula.and(
                Formula.atom("approuve_document_a"), Formula.atom("approuve_document_b")))),
                "instantané du domaine sans nouvelle entité C");
        ok(decided.report().questionGainBits().isPresent() &&
                decided.report().questionGainBits().getAsDouble()>0,"progrès évalué depuis Q et non depuis clarification");
        ok(d.cycle().questionActCount()==before+1,"aucun Q supplémentaire à la clarification");
        ok(d.cycle().journal().stream().filter(e->e.operation().equals("QUESTION_POSED")).count()==1,
                "journal un unique acte Q");
        ok(decided.text().contains("Lecture précisée"),"lecture sélectionnée documentée en réponse");
        var oldPremises=d.cycle().premises().size();
        ok(d.cycle().premises().size()==premBefore+2 && oldPremises==premBefore+2,
                "clarification sans enseignement factuel");
        // Dans un nouveau dialogue : au moins une propriété vraie signifie NON à « aucun ».
        var e=docs();
        e.teach("Le document A n'est pas validé.");
        e.teach("Le document B est validé.");
        var eq=e.ask(scopeQ());
        var neg=e.clarify(eq.questionId(),"aucun");
        ok(neg.report().answer().equals(Optional.of(false)),"aucun est réfuté par B validé");
        ok(neg.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "négation prouvable conditionnellement");
        ok(!neg.report().question().equals(decided.report().question()),"deux questions de portée distincte");
        ok(e.cycle().questionActCount()==1,"clarifier n'ouvre pas une nouvelle obligation");
        // Le féminin pluriel donne les mêmes règles de portée.
        var portes=new FrenchQuestionDialogue();
        portes.declare("porte","A"); portes.declare("porte","B");
        var qp=portes.ask("Est-ce que toutes les portes ne sont pas ouvertes ?");
        ok(qp.pending() && portes.clarify(qp.questionId(),"pas_tous").report().question()
                .equals(Formula.not(Formula.and(Formula.atom("ouvert_porte_a"),Formula.atom("ouvert_porte_b")))),
                "quantificateur féminin et accord adjectival correctement interprétés");
        // Les deux lectures peuvent être simultanément vraies si aucun n'est validé.
        var f=docs();
        f.teach("Le document A n'est pas validé.");
        f.teach("Le document B n'est pas validé.");
        var fq=f.ask(scopeQ());
        ok(f.clarify(fq.questionId(),"aucun").report().answer().equals(Optional.of(true)),
                "aucun vrai quand tous sont non validés");
        // Une contradiction globale ne peut pas fermer une question de portée.
        var contradiction=docs();
        contradiction.teach("Le document A est validé.");
        contradiction.teach("Le document A n'est pas validé.");
        var cq=contradiction.ask(scopeQ());
        ok(contradiction.clarify(cq.questionId(),"pas_tous").report().status()
                ==IntegratedQuestionCycle.Status.INCONSISTENT,"contradiction non exploitée pour fermer FCI");
        // Deux limites sur le MÊME acte Q : polysémie puis portée de la négation.
        var doubleAmbiguity=docs();
        doubleAmbiguity.registerAmbiguousAdjective("juridique",Set.of("document"),
                "validé","validée","validés","validées");
        var dual=doubleAmbiguity.ask(scopeQ());
        ok(dual.pending() && dual.text().contains("juridique") && dual.text().contains("approuve"),
                "première ambiguïté lexicale reconnue");
        var lexical=doubleAmbiguity.clarify(dual.questionId(),"approuve");
        ok(lexical.pending() && lexical.text().contains("pas_tous"),
                "clarification lexicale mène à clarification de portée");
        ok(doubleAmbiguity.cycle().questionActCount()==1,
                "toujours une seule FCI après clarification lexicale");
        doubleAmbiguity.teach("Le document A n'est pas approuvé.");
        var scoped=doubleAmbiguity.clarify(dual.questionId(),"pas_tous");
        ok(scoped.report().answer().equals(Optional.of(true)),
                "résultat postérieur utilise exactement le sens choisi");
        ok(scoped.questionId().equals(dual.questionId())
                && doubleAmbiguity.cycle().questionActCount()==1,
                "deux clarifications conservent le même Q");
        ok(scoped.report().questionGainBits().isPresent()
                && scoped.report().questionGainBits().getAsDouble()>0,
                "snapshot de Q conservé à travers deux clarifications");
        // Le lexique changé ne doit pas faire appliquer un sens différent en cache.
        var stale=docs();
        var qstale=stale.ask(scopeQ());
        stale.registerAdjective("autre",Set.of("document"),"testeur","testeur","testeurs","testeurs");
        rejects(()->stale.clarify(qstale.questionId(),"aucun"),"choix interdit sous empreinte lexicale différente");
        ok(stale.status(qstale.questionId()).pending(),"obligation conservée après refus lexique");
        var cancel=stale.withdraw(qstale.questionId());
        ok(cancel.text().contains("retirée"),"retrait terminal journalisé");
        ok(stale.cycle().journal().stream().anyMatch(j->j.operation().equals("CLARIFICATION_REFUSED")),
                "retrait marqué au journal");
        rejects(()->stale.clarify(qstale.questionId(),"aucun"),"aucune clarification après retrait");
        // Accord et domaine exigés : aucune fausse lecture n'est admise.
        var one=new FrenchQuestionDialogue();
        one.declare("document","A");
        for(var bad:List.of(
                one.ask(scopeQ()),
                d.ask("Est-ce que toutes les documents ne sont pas validés ?"),
                d.ask("Est-ce que tous les documents ne sont pas multicolores ?"))) {
            ok(bad.pending(),"non-interprétation traçable, aucune vérité supposée");
        }
        ok(one.cycle().questionActs().stream().allMatch(q->q.state()==IntegratedQuestionCycle.ActState.UNINTERPRETED),
                "quantification indécidable sans fausse lecture");
        // Traces de preuve : explication de modus ponens, vérifiée et conditionnelle.
        var why=new FrenchQuestionDialogue();
        why.declare("document","A");
        var sourceIds=why.teach("Si le document A est validé, alors le document A est archivé.");
        why.teach("Le document A est validé.");
        var asked=why.ask("Le document A est-il archivé ?");
        ok(asked.report().answer().equals(Optional.of(true)),"conséquence déduite");
        int nQ=why.cycle().questionActCount();
        int nPrem=why.cycle().premises().size();
        var exp=why.explain(asked.questionId());
        ok(exp.text().contains("modus ponens"),"explication à partir du terme de preuve");
        ok(exp.text().contains("CONDITIONNELLEMENT"),"ne transforme pas une prémisse en vérité vérifiée");
        ok(exp.text().contains("archivé"),"conclusion française");
        ok(exp.text().contains(sourceIds.get(0)),"identifiant de source cité");
        ok(why.cycle().questionActCount()==nQ && why.cycle().premises().size()==nPrem,
                "expliquer un Q n'en crée pas un second ni de fait");
        // Question répondue directement : pas de fausse démonstration indépendante.
        var direct=new FrenchQuestionDialogue();
        direct.declare("document","A");
        direct.teach("Le document A est validé.");
        var dq=direct.ask("Le document A est-il validé ?");
        var de=direct.explain(dq.questionId());
        ok(de.text().contains("Hypothèse") || de.text().contains("aucune démonstration"),
                "ne revendique pas une indépendance probatoire");
        // Lecture via console, l'explication reste une commande sans nouvel acte Q.
        String explainScript="entite document A\nsais Si le document A est validé, alors le document A est archivé.\n"
            +"sais Le document A est validé.\n? Le document A est-il archivé ?\nexplique Q1\nquitter\n";
        ByteArrayOutputStream explained=new ByteArrayOutputStream();
        FrenchConsole.run(new ByteArrayInputStream(explainScript.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(explained,true,StandardCharsets.UTF_8));
        ok(explained.toString(StandardCharsets.UTF_8).contains("Par modus ponens"),
                "commande explique Q1 produit une dérivation vérifiée");
        // Rétractation : un ancien certificat ne doit pas servir d'explication courante.
        why.retract(sourceIds.get(0));
        var gone=why.explain(asked.questionId());
        ok(!gone.text().contains("Par modus ponens") && gone.text().contains("indisponible"),
                "pas d'explication périmée après rétractation");
        rejects(()->why.explain("Q999"),"Q inexistant ne fabrique pas une réponse");
        // Interface console complète, sans fuite du corpus indépendant.
        String script="entite document A\nentite document B\n"
            +"sais Le document A n'est pas validé.\n? "+scopeQ()+"\n"
            +"precise Q1 pas_tous\nquestions\nquitter\n";
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        FrenchConsole.run(new ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out,true,StandardCharsets.UTF_8));
        String output=out.toString(StandardCharsets.UTF_8);
        ok(output.contains("Deux lectures de la négation"),"console fournit clarification de portée");
        ok(output.contains("Lecture précisée"),"console utilise le choix utilisateur");
        ok(!output.contains("Non interprété"),"console exécute le scénario normal");
        return n;
    }
    public static void main(String[] args) {System.out.println("LING-004 OK - "+runAll());}
}
