package fr.ivorama.cognitive;

import java.util.*;

/** LING-006 development scenarios, deliberately NOT the independent evaluation corpus. */
public final class RelationalSemanticTest {
    private static int checked;
    private static void ok(boolean claim,String why) {
        checked++;
        if(!claim) throw new AssertionError("LING-006: "+why);
    }
    private static void reject(Runnable code,String why) {
        boolean rejected=false;
        try { code.run(); }
        catch(IllegalArgumentException|IllegalStateException expected) {rejected=true;}
        ok(rejected,why);
    }
    public static int runAll() {
        checked=0;
        ControlledFrench language=new ControlledFrench();
        language.declareEntity("document","a");
        language.declareEntity("dossier","b");
        language.declareEntity("document","c");
        language.declareEntity("dossier","d");
        language.declareEntity("porte","e");
        RelationalFrench rel=new RelationalFrench(language);
        String atom="relation_contient_document_a_dossier_b";
        var f=Formula.atom(atom);
        var direct=rel.analyse("Le document A contient le dossier B.");
        ok(direct.frame().relation().equals("contient"),"typed relation ID");
        ok(direct.frame().subject().name().equals("a"),"correct subject");
        ok(direct.frame().object().name().equals("b"),"correct object");
        ok(!direct.frame().negated(),"positive polarity");
        ok(direct.proposition().equals(f),"canonical atom");
        ok(direct.act()==ControlledFrench.Act.ASSERT,"assertion act");
        ok(rel.analyse("Le dossier B est contenu dans le document A.").proposition().equals(f),
                "reversible lexical paraphrase");
        ok(rel.analyse("Le dossier B est-il contenu dans le document A ?").proposition().equals(f),
                "passive inversion in interrogative preserves relational roles");
        reject(()->rel.analyse("Le dossier B est-elle contenu dans le document A ?"),
                "wrong inverted pronoun cannot be accepted");
        ok(rel.analyse("Est-ce que le document A contient le dossier B ?").proposition().equals(f),
                "question meaning invariant");
        ok(rel.analyse("Le document A contient le dossier B ?").act()==ControlledFrench.Act.ASK,
                "relational question intent");
        ok(rel.analyse("Le dossier B est-il contenu dans le document A ?").proposition().equals(f),
                "passive inversion preserves canonical meaning");
        ok(rel.analyse("Le dossier B n'est-il pas contenu dans le document A ?").proposition()
                .equals(Formula.not(f)),"negative passive inversion preserves polarity");
        reject(()->rel.analyse("Le dossier B est-elle contenu dans le document A ?"),
                "invalid inversion gender refused");
        ok(rel.analyse("Le document A ne contient pas le dossier B.").proposition().equals(Formula.not(f)),
                "active negation");
        ok(rel.analyse("Le dossier B n'est pas contenu dans le document A.").proposition().equals(Formula.not(f)),
                "inverse negation same formula");
        ok(!rel.looksRelational("Le document A est validé."),"unary fact not relational");
        ok(!rel.looksRelational("Le document A est archivé et validé."),"unary coordination not relational");
        ok(rel.entries().size()==2,"initial relation count");
        reject(()->rel.register("c2","document","dossier","contient","est abrité par"),
                "no silent collisions on identical surface");
        reject(()->rel.register("contient","document","porte","contient","est contenu dans"),
                "no semantic repurposing");
        reject(()->rel.register("mal_forme","document","dossier","se trouve dans","est lié à"),
                "multiword active verbs outside published grammar");
        reject(()->rel.analyse("La document A contient le dossier B."),"bad determiner");
        reject(()->rel.analyse("Le document A contient la dossier B."),"bad object determiner");
        reject(()->rel.analyse("Le dossier B contient le document A."),"bad direction");
        reject(()->rel.analyse("Le document A contient la porte E."),"wrong object type");
        reject(()->rel.analyse("Le document X contient le dossier B."),"unknown subject");
        reject(()->rel.analyse("Le document A contient le dossier X."),"unknown object");
        reject(()->rel.analyse("Est-ce que le document A contient le dossier B."),
                "explicit question prefix missing question punctuation");
        reject(()->rel.analyse("Le document A contient le dossier B et le dossier D."),
                "coordination must not be silently interpreted as relation");
        ok(rel.analyse("Le document C contient le dossier D.").frame().object().name().equals("d"),
                "different entities produce separate frame");
        ok(!rel.analyse("Le document C contient le dossier D.").proposition().equals(f),
                "no unification of independent objects");
        var conditional=rel.tryConditional("Si le document A contient le dossier B, alors le document A est validé.")
                .orElseThrow();
        ok(conditional.formulas().get(0).equals(Formula.implies(f,Formula.atom("approuve_document_a"))),
                "relational premise in conditional");
        var conditional2=rel.tryConditional("Si le document A est validé, alors le document A contient le dossier B.")
                .orElseThrow();
        ok(conditional2.formulas().get(0).equals(Formula.implies(Formula.atom("approuve_document_a"),f)),
                "relational conclusion in conditional");
        ok(rel.tryConditional("Si le document A est validé, alors le document A est archivé.").isEmpty(),
                "unary conditional left to legacy grammar");
        var beforeCount=language.entities().size();
        rel.register("repere","document","dossier","repère","est repéré par");
        ok(rel.entries().size()==3,"new relation registered");
        ok(language.entities().size()==beforeCount,"relation vocabulary does not create entities");
        ok(rel.analyse("Le dossier B est repéré par le document A.").proposition().equals(
                Formula.atom("relation_repere_document_a_dossier_b")),
                "new meaning composed without recompiling rules");
        reject(()->rel.analyse("Le dossier B est contenu dans la porte E."),"inverse wrong kind rejected");

        FrenchQuestionDialogue d=new FrenchQuestionDialogue();
        d.declare("document","a");d.declare("dossier","b");
        int base=d.cycle().questionActCount();
        int prem=d.cycle().premises().size();
        d.registerRelation("oriente","document","dossier","oriente","est orienté par");
        ok(d.cycle().questionActCount()==base,"lexical registration never creates FCI");
        ok(d.cycle().premises().size()==prem,"lexical registration never creates fact");
        var q=d.ask("Le dossier B est orienté par le document A ?");
        ok(q.report().status()==IntegratedQuestionCycle.Status.OPEN,"unknown relationship stays unknown");
        ok(d.cycle().questionActCount()==base+1,"one Ask creates exactly one FCI");
        var facts=d.teach("Le document A oriente le dossier B.");
        ok(facts.size()==1,"one relational declaration creates one premise");
        var justified=d.status(q.questionId());
        ok(justified.report().answer().equals(Optional.of(true)),"inverse paraphrase repaired");
        ok(justified.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "declarative provenance remains conditional");
        ok(d.cycle().questionActCount()==base+1,"re-evaluation is not a new Ask");
        d.retract(facts.get(0));
        ok(d.status(q.questionId()).report().reopened(),"retraction reopens original FCI");
        var negative=d.teach("Le document A ne oriente pas le dossier B.");
        ok(negative.size()==1,"explicit negative relational premise (controlled grammar)");
        ok(d.status(q.questionId()).report().answer().equals(Optional.of(false)),
                "explicit negative evidence for relation, not closed-world reasoning");
        var why=d.ask("Pourquoi le document A oriente le dossier B ?");
        ok(d.cycle().questionActCount()==base+2,"why is distinct FCI");
        ok(why.pending(),"positive explanation not faked by negative evidence");
        var mixed=d.ask("Est-ce que le document A est validé et le document A oriente le dossier B ?");
        ok(mixed.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && mixed.report().answer().equals(Optional.of(false)),
                "explicit negative evidence refutes conjunction even if other conjunct unknown");
        ok(d.cycle().questionActCount()==base+3,"mixed question exactly one Ask");
        d.teach("Le document A est validé.");
        ok(d.status(mixed.questionId()).report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                || d.status(mixed.questionId()).report().status()==IntegratedQuestionCycle.Status.OPEN,
                "mixed conjunction never assumes absence as true");
        d.retract(negative.get(0));
        var positive=d.teach("Le document A oriente le dossier B.");
        ok(d.status(mixed.questionId()).report().answer().equals(Optional.of(true)),
                "mixed conjunction proven when both facts supplied");
        d.retract(positive.get(0));
        ok(d.status(mixed.questionId()).report().reopened(),"compound relation question reopens");

        // Deterministic generated DEVELOPING cases, not the blind 100 cases.
        for(int i=0;i<50;i++) {
            String a="a"+i,b="b"+i;
            ControlledFrench g=new ControlledFrench();
            g.declareEntity("document",a);g.declareEntity("dossier",b);
            RelationalFrench r=new RelationalFrench(g);
            var a1=r.analyse("Le document "+a+" contient le dossier "+b+".");
            var a2=r.analyse("Le dossier "+b+" est contenu dans le document "+a+".");
            ok(a1.proposition().equals(a2.proposition()),"generated paraphrase isomorphism "+i);
            ok(r.analyse("Le dossier "+b+" n'est pas contenu dans le document "+a+".")
                    .proposition().equals(Formula.not(a1.proposition())),"generated polarity "+i);
            ok(Formula.atoms(a1.proposition()).size()==1,"generated binary atom "+i);
        }
        return checked;
    }
    public static void main(String[] args) {
        System.out.println("RELATIONAL-SEMANTIC TEST OK - "+runAll()+" assertions");
    }
}
