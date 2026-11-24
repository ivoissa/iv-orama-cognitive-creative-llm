package fr.ivorama.cognitive;

import java.util.*;

/**
 * Revue IVOC-LING-001 (reproduction des défauts D1 et D7/D7b).
 * Tests de développement, distincts des 100 questions indépendantes.
 * L'oracle examine exhaustivement les modèles sur un budget plus élevé.
 */
public final class ReviewFnd005AuditTest {
    private static int checks;
    private ReviewFnd005AuditTest() {}
    private static void ok(boolean value,String reason) {
        checks++;if(!value)throw new AssertionError("AUDIT 005 : "+reason);
    }
    private static void reject(Runnable test,String reason) {
        boolean rejected=false;
        try{test.run();}catch(IllegalArgumentException|IllegalStateException e){rejected=true;}
        ok(rejected,reason);
    }

    public static int runAll() {
        checks=0;
        FrenchQuestionDialogue dialogue=new FrenchQuestionDialogue();
        dialogue.declare("document","A");
        reject(()->dialogue.teach("Est ce que le document A est validé."),
                "D1 unpunctuated interrogative cannot become teaching assertion");
        reject(()->dialogue.teach("Est-ce que le document A est validé."),
                "D1 hyphenated interrogative cannot become assertion");
        reject(()->dialogue.teach("Le document A est-il validé."),
                "D1 inverted question cannot become assertion");
        ok(dialogue.cycle().questions().isEmpty() && dialogue.cycle().premises().isEmpty(),
                "D1 malformed teaching creates neither fact nor Q");
        ok(dialogue.ask("Est ce que le document A est validé ?").report().status()
                ==IntegratedQuestionCycle.Status.OPEN,
                "D1 punctuated variant is an actual question, not a fact");

        ControlledFrench grammar=new ControlledFrench();
        grammar.declareEntity("document","A");grammar.declareEntity("document","B");
        var finite=grammar.interpret("Tous les documents sont valides.");
        ok(finite.act()==ControlledFrench.Act.UNIVERSAL && finite.formulas().size()==2,
                "natural universal without qualifier grounded on two entities");
        ok(finite.formulas().contains(Formula.atom("valide_document_a")) &&
                finite.formulas().contains(Formula.atom("valide_document_b")),
                "finite universal maps to facts, not unsupported world-open quantifier");
        ok(grammar.frozenKinds().isEmpty(),"mere interpretation cannot freeze categories");
        grammar.commit(finite);
        reject(()->grammar.declareEntity("document","C"),"universal freezes finite category after commit");
        ControlledFrench tautology=new ControlledFrench();
        tautology.declareEntity("document","A");
        reject(()->tautology.interpret("Tous les documents valides sont valides."),
                "tautological universal cannot freeze domain without information");
        ok(tautology.frozenKinds().isEmpty(),"rejected tautology has no side effects");

        ModelExplorer.Budget standard=ModelExplorer.Budget.small();
        List<Formula> facts=new ArrayList<>();
        for(int i=0;i<13;i++) facts.add(Formula.atom("fact"+i));
        Formula target=Formula.atom("fact0");
        var optimized=ModelExplorer.analyze(facts,target,standard);
        ok(optimized.status()==ModelExplorer.Status.PROVEN_TRUE,
                "D7 13 independent facts have an answer even when total atom count exceeds 12");
        ok(!optimized.completeWorlds() && optimized.possibleWorlds().isEmpty(),
                "D7 partial result never claims global possibleWorlds enumeration");
        ok(optimized.trueWitness().isPresent() && optimized.trueWitness().get().values().size()==13,
                "D7 witness contains all input atoms");
        ok(optimized.trueWitness().get().satisfies(target) &&
                facts.stream().allMatch(optimized.trueWitness().get()::satisfies),
                "D7 complete witness actually satisfies all premises");
        ok(ModelExplorer.analyzeFull(facts,target,standard).status()==ModelExplorer.Status.RESOURCE_LIMIT,
                "full-world consumers are not silently given projected worlds");
        List<Formula> inconsistent=new ArrayList<>(facts);
        inconsistent.add(Formula.not(Formula.atom("fact12")));
        ok(ModelExplorer.analyze(inconsistent,target,standard).status()==ModelExplorer.Status.INCONSISTENT,
                "globally contradictory component cannot be ignored because unrelated to question");
        Formula coupled=Formula.atom("b0");
        for(int i=1;i<13;i++) coupled=Formula.and(coupled,Formula.atom("b"+i));
        ok(ModelExplorer.analyze(List.of(coupled),Formula.atom("b0"),standard).status()==ModelExplorer.Status.PROVEN_TRUE,
                "2-CNF conjunction across 13 atoms is now decidable without exhaustive worlds");
        Formula nonTwoSat=Formula.implies(coupled,Formula.atom("b13"));
        ok(ModelExplorer.analyze(List.of(nonTwoSat),Formula.atom("b13"),standard).status()==ModelExplorer.Status.RESOURCE_LIMIT,
                "13+ coupled atoms outside 2-CNF remain bounded");

        FrenchQuestionDialogue large=new FrenchQuestionDialogue();
        for(char name='A';name<='G';name++) large.declare("document",String.valueOf(name));
        large.teach("Tous les documents validés sont archivés.");
        large.teach("Le document A est validé.");
        ok(large.ask("Le document A est-il archivé ?").report().answer().equals(Optional.of(true)),
                "7 entities / 14 atoms: integrated Q receives a valid conditional answer");

        FrenchQuestionDialogue awaiting=new FrenchQuestionDialogue();
        for(char name='A';name<='G';name++) awaiting.declare("document",String.valueOf(name));
        awaiting.teach("Tous les documents validés sont archivés.");
        awaiting.registerObservable("Le document A est validé.");
        var pending=awaiting.ask("Le document A est-il archivé ?");
        ok(pending.report().status()==IntegratedQuestionCycle.Status.NEED_INFORMATION,
                "D7 RDO uses relevant local worlds to request observation despite >12 global atoms");
        ok(pending.report().requestedObservation().equals(Optional.of(Formula.atom("approuve_document_a"))),
                "D7 RDO requests relevant predicate only, not another independent entity");
        var completed=awaiting.answerObservation(pending.questionId(),true,"Utilisateur - non vérifié");
        ok(completed.report().answer().equals(Optional.of(true))
                && completed.report().status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "D7 Q closes conditionally following a relevant observation");

        // Oracle comparison: full enumeration on <=6 atoms vs reduced component algorithm
        // with 3-atom local budget. Distinct sets of observations are not benchmark items.
        Random random=new Random(20261012);
        for(int run=0;run<40;run++) {
            List<Formula> premises=new ArrayList<>();
            Formula[] atom=new Formula[6];
            for(int i=0;i<6;i++)atom[i]=Formula.atom("u"+i);
            for(int i=0;i<6;i+=2) {
                if(random.nextBoolean())premises.add(Formula.implies(atom[i],atom[i+1]));
                else premises.add(Formula.implies(atom[i+1],atom[i]));
                if(random.nextBoolean())premises.add(atom[i]);
                if(random.nextBoolean())premises.add(Formula.not(atom[i+1]));
            }
            Formula question=atom[random.nextInt(6)];
            var expected=ModelExplorer.analyzeFull(premises,question,new ModelExplorer.Budget(12,4096));
            var actual=ModelExplorer.analyze(premises,question,new ModelExplorer.Budget(3,128));
            ok(actual.status()==expected.status(),"differential oracle run "+run);
            ok(!actual.completeWorlds() && actual.possibleWorlds().isEmpty(),
                    "non-exhaustive result is explicit, run "+run);
            if(actual.trueWitness().isPresent())
                ok(actual.trueWitness().get().satisfies(question) &&
                        premises.stream().allMatch(actual.trueWitness().get()::satisfies),
                        "positive witness valid run "+run);
            if(actual.falseWitness().isPresent())
                ok(!actual.falseWitness().get().satisfies(question) &&
                        premises.stream().allMatch(actual.falseWitness().get()::satisfies),
                        "negative witness valid run "+run);
        }
        return checks;
    }
    public static void main(String[] args) {
        System.out.println("AUDIT 005 OK - "+runAll()+" assertions");
    }
}
