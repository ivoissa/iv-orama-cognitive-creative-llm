package fr.ivorama.cognitive;

import java.nio.file.*;
import java.util.*;

/** Oracle constructif et invariants : quantificateur à restricteur relationnel. */
public final class QuantifiedRelationTest {
    private static int assertions;
    private static void ok(boolean actual,String why) {
        assertions++;if(!actual)throw new AssertionError(why);
    }
    private static void rejects(Runnable code,String why) {
        boolean fail=false;try{code.run();}catch(IllegalArgumentException|IllegalStateException ex){fail=true;}
        ok(fail,why);
    }
    private static Optional<Boolean> answer(FrenchQuestionDialogue d,String id) {
        return d.status(id).report().answer();
    }
    public static int runAll() throws Exception {
        assertions=0;
        FrenchQuestionDialogue d=new FrenchQuestionDialogue();
        d.declare("document","a");d.declare("document","c");
        d.declare("dossier","b");d.declare("dossier","z");
        int q0=d.cycle().questionActCount();
        int p0=d.cycle().premises().size();
        var lang=d.language();
        QuantifiedRelationalFrench parser=new QuantifiedRelationalFrench(lang,d.relations());
        String rule="Tous les documents qui contiennent un dossier sont complets.";
        var parsed=parser.parse(rule).orElseThrow();
        ok(parsed.interpretation().act()==ControlledFrench.Act.UNIVERSAL,"typed quantified act");
        ok(parsed.interpretation().formulas().size()==4,"2x2 independent clauses");
        ok(q0==d.cycle().questionActCount()&&p0==d.cycle().premises().size(),"no knowledge from parse");
        ok(lang.frozenKinds().isEmpty(),"parsing never freezes domain");
        Formula r_ab=Formula.atom("relation_contient_document_a_dossier_b");
        Formula c_a=Formula.atom("complet_document_a");
        ok(parsed.interpretation().formulas().contains(Formula.implies(r_ab,c_a)),"correct implication and roles");
        d.teach(rule);
        ok(lang.frozenKinds().containsAll(Set.of("document","dossier")),"both domains frozen after admission");
        rejects(()->d.declare("document","new"),"subject domain remains frozen");
        rejects(()->d.declare("dossier","new"),"object domain remains frozen");
        var qa=d.ask("Le document a est-il complet ?");
        var qc=d.ask("Le document c est-il complet ?");
        ok(answer(d,qa.questionId()).isEmpty(),"universal without instantiated relation is not unconditional truth");
        ok(answer(d,qc.questionId()).isEmpty(),"unrelated subject remains unknown");
        var supports=d.teach("Le document a contient le dossier b.");
        ok(answer(d,qa.questionId()).equals(Optional.of(true)),"quantified rule with relation proves property");
        ok(answer(d,qc.questionId()).isEmpty(),"no cross-subject proof leakage");
        ok(d.status(qa.questionId()).report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
           "conclusion remains conditional on declarations");
        d.retract(supports.get(0));
        ok(answer(d,qa.questionId()).isEmpty(),"retraction reopens the same question");
        d.teach("Le document a contient le dossier z.");
        ok(answer(d,qa.questionId()).equals(Optional.of(true)),"alternative object yields same existential premise");
        ok(q0+2==d.cycle().questionActCount(),"learning a rule, no new FCI");

        FrenchQuestionDialogue learned=new FrenchQuestionDialogue();
        learned.declare("document","x");learned.declare("dossier","y");
        learned.registerRelation("surveille","document","dossier","surveille","est surveillé par","surveillent");
        learned.teach("Tous les documents qui surveillent un dossier sont actifs.");
        var active=learned.ask("Le document x est-il actif ?");
        ok(answer(learned,active.questionId()).isEmpty(),"registered verb does not create fact");
        learned.teach("Le document x surveille le dossier y.");
        ok(answer(learned,active.questionId()).equals(Optional.of(true)),"newly learned relation under quantified rule");

        for(int n=1;n<=4;n++) {
            FrenchQuestionDialogue a=new FrenchQuestionDialogue();
            a.declare("document","p");
            for(int j=0;j<n;j++)a.declare("dossier","z"+j);
            a.teach(rule);
            var question=a.ask("Le document p est-il complet ?");
            ok(answer(a,question.questionId()).isEmpty(),"all relations absent => no proof n="+n);
            a.teach("Le document p contient le dossier z"+(n-1)+".");
            ok(answer(a,question.questionId()).equals(Optional.of(true)),"any individual relation suffices n="+n);
        }
        ControlledFrench grammar=new ControlledFrench();grammar.declareEntity("document","a");grammar.declareEntity("dossier","b");
        var rel=new RelationalFrench(grammar);
        var controlled=new QuantifiedRelationalFrench(grammar,rel);
        rejects(()->controlled.parse("Toutes les documents qui contiennent un dossier sont complets."),
            "quantifier mismatch refused");
        rejects(()->controlled.parse("Tous les documents qui contiennent une dossier sont complets."),
            "object determiner mismatch refused");
        rejects(()->controlled.parse("Tous les documents qui finissent un dossier sont complets."),
            "unknown verb refused");
        ok(controlled.parse("Tous les documents qui contiennent un dossier sont complets ?").isEmpty(),
            "question must not be parsed as assertion");
        rejects(()->controlled.parse("Tous les documents qui contiennent un dossier sont inconnus."),
            "unknown consequent adjective refused");
        ok(grammar.frozenKinds().isEmpty(),"invalid interpretations never freeze");
        var r=Development100.evaluate(Path.of("evaluation/dev100/SCENARIOS_100_DEVELOPPEMENT.tsv"));
        ok(r.passed()==75&&r.gaps()==25&&r.failed()==0,"independent DEV100 runner score from unchanged catalogue");
        ok(r.activatedIds().equals(Set.of("TRA-06","DIA-04")),"activation transparent and explicit");
        ok(r.sourceHash().equals("08f9cedce8b4107d8292f6c61ebfe0c5c375800162371ccd598ba4fef4cde81f"),
           "fixed original corpus hash");
        FrenchQuestionDialogue ambiguous=new FrenchQuestionDialogue();
        ambiguous.declare("document","a");
        ambiguous.registerAdjective("x1",Set.of("document"),"pur","pur","purs","purs");
        ambiguous.registerAmbiguousAdjective("x2",Set.of("document"),"pur","pur","purs","purs");
        var question=ambiguous.ask("Le document a est-il pur ?");
        ok(question.pending(),"ambiguous word opens a single FCI");
        int fciCount=ambiguous.cycle().questionActCount();
        int factCount=ambiguous.cycle().premises().size();
        var refused=ambiguous.tryClarify(question.questionId(),"inexistant");
        ok(!refused.accepted() && refused.reply().pending(),"typed rejection remains pending");
        ok(refused.reply().text().contains("REFUS"),"explicitly visible refusal");
        ok(ambiguous.pendingIds().contains(question.questionId()),"same question remains active");
        ok(ambiguous.cycle().questionActCount()==fciCount,"refusal creates no FCI");
        ok(ambiguous.cycle().premises().size()==factCount,"refusal creates no fact");
        ok(ambiguous.rejectedClarifications(question.questionId()).equals(List.of("inexistant")),
           "rejection audited distinctly");
        ok(ambiguous.tryClarify(question.questionId(),"x1").accepted(),"valid choice can follow rejection");
        ok(ambiguous.cycle().questionActCount()==fciCount,"successful clarification reuses same Q");
        RelationalFrench lex=new RelationalFrench(new ControlledFrench());
        int oldEntries=lex.entries().size();
        rejects(()->lex.register("essai","document","dossier","essaye","est essayé par","erreur1"),
                "invalid plural does not register relation");
        ok(lex.entries().size()==oldEntries,"registration atomic on invalid morphology");
        return assertions;
    }
    public static void main(String[] args) throws Exception {
        System.out.println("QUANTIFIED RELATIONS OK - "+runAll()+" assertions");
    }
}
