package fr.ivorama.cognitive;

import java.util.*;

/** Regressions inspired by the external IVOC-LING-003 review, adapted to 0.5.8. */
public final class ClaudeContributionTest {
    private static int assertions;
    private static void check(boolean ok,String why) {assertions++; if(!ok) throw new AssertionError(why);}
    private static void expectsIllegal(Runnable action,String why) {
        try {action.run(); throw new AssertionError("Expected rejection: "+why);}
        catch(IllegalStateException | IllegalArgumentException expected) {assertions++;}
    }
    private static Formula lit(Random r,int n) {
        Formula a=Formula.atom("r"+r.nextInt(n));
        return r.nextBoolean()?a:Formula.not(a);
    }
    public static int runAll() {
        // L5 - no artificial formula for pending Q, but every Q has a typed view.
        var d=new FrenchQuestionDialogue(); d.declare("document","d1");
        d.registerAmbiguousAdjective("ouvert_juridique",Set.of("document"),"ouvert","ouverte","ouverts","ouvertes");
        var q=d.ask("Le document d1 est-il ouvert ?");
        check(q.pending(),"lexical question is pending");
        check(d.cycle().questionActs().size()==1 && d.cycle().questionActCount()==1,"one Q in exhaustive registry");
        check(d.cycle().questionAct(q.questionId()).state()==IntegratedQuestionCycle.ActState.NEEDS_CLARIFICATION,"typed state visible");
        check(d.cycle().questionAct(q.questionId()).formula().isEmpty(),"pending Q has no invented formula");
        // L6 - bypass of pending decisions is rejected, but raw core remains usable.
        expectsIllegal(()->d.cycle().resolveUnresolved(q.questionId(),Formula.atom("open_document_d1")),"no bypass");
        check(d.pendingIds().size()==1 && d.cycle().unresolvedQuestions().size()==1,"same pending state");
        var answered=d.clarify(q.questionId(),"ouvert_juridique");
        check(!answered.pending(),"clarification completed");
        check(d.cycle().questionAct(q.questionId()).state()==IntegratedQuestionCycle.ActState.INTERPRETED,"same question id");
        var core=new IntegratedQuestionCycle();
        String raw=core.openUnresolved("? p","p",List.of("p1","p2"));
        core.resolveUnresolved(raw,Formula.atom("p"));
        check(core.questionActCount()==1,"public standalone cycle backward compatibility");
        // L8 - reserved conjunction cannot be declared as an entity identifier.
        expectsIllegal(()->new FrenchQuestionDialogue().declare("document","et"),"reserved word");
        // Test question withdrawal is terminal, auditable, and does not increment act count.
        var d2=new FrenchQuestionDialogue();d2.declare("document","d1");
        d2.registerAmbiguousAdjective("ouvert_juridique",Set.of("document"),"ouvert","ouverte","ouverts","ouvertes");
        var pending=d2.ask("Le document d1 est-il ouvert ?");
        d2.withdraw(pending.questionId());
        check(d2.cycle().questionActCount()==1,"one act after withdrawal");
        check(d2.cycle().questionAct(pending.questionId()).state()==IntegratedQuestionCycle.ActState.WITHDRAWN,"terminal withdrawal visible");

        // Extension 2-SAT: p -> (q AND r), question q AND r, semantics and witnesses.
        Formula p=Formula.atom("p"), qq=Formula.atom("q"), rr=Formula.atom("r");
        var kb=List.of(p,Formula.implies(p,Formula.and(qq,rr)));
        var extension=TwoSatEntailment.analyze(kb,Formula.and(qq,rr),List.of());
        check(extension.isPresent() && extension.get().status()==ModelExplorer.Status.PROVEN_TRUE,"2SAT implication to conjunction");
        check(extension.get().trueWitness().isPresent() && extension.get().trueWitness().get().satisfies(Formula.and(qq,rr)),"positive witness");
        var uncertain=TwoSatEntailment.analyze(List.of(Formula.implies(p,qq)),Formula.and(p,rr),List.of());
        check(uncertain.isPresent() && uncertain.get().status()==ModelExplorer.Status.UNDETERMINED,"underdetermined conjunction");
        check(uncertain.get().falseWitness().isPresent() && !uncertain.get().falseWitness().get().satisfies(Formula.and(p,rr)),"negative witness");
        var impossible=TwoSatEntailment.analyze(List.of(p,Formula.not(qq)),Formula.and(p,qq),List.of());
        check(impossible.isPresent() && impossible.get().status()==ModelExplorer.Status.PROVEN_FALSE,"conjunctive question refuted");
        // Outside 2-CNF must always reject optimization rather than guess.
        var outside=TwoSatEntailment.analyze(List.of(Formula.implies(Formula.and(p,qq),rr)),rr,List.of());
        check(outside.isEmpty(),"outside fragment fallback");

        Random random=new Random(20261013L);
        for(int trial=0;trial<2000;trial++) {
            int atoms=1+random.nextInt(6);
            List<Formula> premises=new ArrayList<>();
            int count=random.nextInt(9);
            for(int j=0;j<count;j++) {
                Formula a=lit(random,atoms),b=lit(random,atoms),c=lit(random,atoms);
                switch(random.nextInt(4)) {
                    case 0 -> premises.add(a);
                    case 1 -> premises.add(Formula.implies(a,b));
                    case 2 -> premises.add(Formula.and(a,b));
                    default -> premises.add(Formula.implies(a,Formula.and(b,c)));
                }
            }
            Formula target=Formula.and(lit(random,atoms),lit(random,atoms));
            var fast=TwoSatEntailment.analyze(premises,target,List.of());
            var oracle=ModelExplorer.analyzeFull(premises,target,ModelExplorer.Budget.small());
            check(fast.isPresent(),"2SAT fragment #"+trial);
            check(fast.get().status()==oracle.status(),"2SAT oracle divergence #"+trial+" "+premises+" ? "+target);
            for(var witness:List.of(fast.get().trueWitness(),fast.get().falseWitness())) if(witness.isPresent()) {
                for(var prem:premises) check(witness.get().satisfies(prem),"invalid premise witness #"+trial);
            }
            if(fast.get().trueWitness().isPresent()) check(fast.get().trueWitness().orElseThrow().satisfies(target),"invalid true witness #"+trial);
            if(fast.get().falseWitness().isPresent()) check(!fast.get().falseWitness().orElseThrow().satisfies(target),"invalid false witness #"+trial);
        }
        // The optimized status may conclude without pretending to enumerate all worlds.
        List<Formula> big=new ArrayList<>();big.add(Formula.atom("x0"));
        for(int i=0;i<30;i++) big.add(Formula.implies(Formula.atom("x"+i),Formula.atom("x"+(i+1))));
        var bigResult=ModelExplorer.analyze(big,Formula.and(Formula.atom("x29"),Formula.atom("x30")),ModelExplorer.Budget.small());
        check(bigResult.status()==ModelExplorer.Status.PROVEN_TRUE && !bigResult.completeWorlds(),"composite long chain without fake enumeration");
        return assertions;
    }
    public static void main(String[] args) { System.out.println("CLAUDE CONTRIBUTIONS OK - "+runAll()+" contrôles"); }
}
