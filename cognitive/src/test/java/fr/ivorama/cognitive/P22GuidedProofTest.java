package fr.ivorama.cognitive;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static fr.ivorama.cognitive.SemanticExplanation.*;
final class P22GuidedProofTest {
    private static Formula a(String n){return Formula.atom(n);}
    private static Premise p(String id,Formula f){return new Premise(id,f,"declared-"+id,"excerpt-"+id);}
    private static List<Premise> simple(){return List.of(p("fact",a("p")),p("rule",Formula.implies(a("p"),a("q"))));}
    private static SemanticExplanation hint(Formula goal,List<Premise> selected){
        return new SemanticExplanation(Kind.IMPLICATION,Status.COMPLETE,ModelExplorer.Status.PROVEN_TRUE,
            Optional.of(goal),Optional.empty(),Optional.empty(),List.of(new Core(selected,true,true)),true,0,0,0,"untrusted","fixture only");
    }
    private static SemanticExplanationProvider fake(SemanticExplanation result){return new SemanticExplanationProvider(){
        public SemanticExplanation explain(Collection<Premise> ps,Formula q){return result;}
        public boolean matches(SemanticExplanation r,Collection<Premise> ps,Formula q){return true;}
    };}
    private static void checked(GuidedProofSearch.Result r,List<Premise> ps,Formula q){
        var v=ProofChecker.verify(r.outcome().proof().orElseThrow(),ps.stream().map(Premise::formula).toList());
        assertTrue(v.valid());assertEquals(q,v.conclusion());
    }
    @Test void realLogicNgHintCanFitWhenFullClosureExceedsCapacity(){
        var ps=new ArrayList<>(simple());for(int i=0;i<5;i++)ps.add(p("noise"+i,Formula.implies(a("u"+i),a("u"+i))));
        var q=a("q");assertEquals(ProofSearch.SearchStatus.RESOURCE_LIMIT,
            ProofSearch.findIndexedWithBudget(ps.stream().map(Premise::formula).toList(),q,3,10000).status());
        var guided=GuidedProofSearch.find(new LogicNgExplanationService(),ps,q,3,10000,2);
        assertEquals(GuidedProofSearch.GuidanceStatus.USED,guided.guidance());
        assertFalse(guided.fallbackUsed());assertEquals(Set.of("fact","rule"),Set.copyOf(guided.selectedSupportIds()));checked(guided,ps,q);
    }
    @Test void staleHintsRetractionsAndAlternativeSourcesAreExplicit(){
        var service=new LogicNgExplanationService();var ps=new ArrayList<>(simple());ps.add(p("alternative",a("p")));
        var q=a("q");var old=service.explain(ps,q);var initial=GuidedProofSearch.find(service,ps,q,32,10000,2);checked(initial,ps,q);
        ps.removeFirst();assertFalse(initial.isCurrent(ps,q));
        var stale=new SemanticExplanationProvider(){public SemanticExplanation explain(Collection<Premise> p,Formula f){return old;}
            public boolean matches(SemanticExplanation r,Collection<Premise> p,Formula f){return service.matches(r,p,f);}};
        var after=GuidedProofSearch.find(stale,ps,q,32,10000,2);
        assertEquals(GuidedProofSearch.GuidanceStatus.STALE,after.guidance());assertTrue(after.fallbackUsed());checked(after,ps,q);
        ps.removeIf(p->p.id().equals("alternative"));assertTrue(GuidedProofSearch.find(stale,ps,q,32,10000,2).outcome().proof().isEmpty());
        var changed=new ArrayList<>(simple());changed.set(0,new Premise("fact",a("p"),"different-source","different-excerpt"));
        assertFalse(initial.isCurrent(changed,q));assertFalse(initial.isCurrent(simple(),a("other")));
    }
    @Test void forgedSourceOrFormulaCannotAuthorizeAnAssumption(){
        var ps=simple();var q=a("q");
        for(var forged:List.of(new Premise("fact",q,"declared-fact","excerpt-fact"),
                new Premise("fact",a("p"),"forged-source","excerpt-fact"),p("technicalNegatedTarget",q))) {
            var r=GuidedProofSearch.find(fake(hint(q,List.of(forged))),ps,q,32,10000,1);
            assertEquals(GuidedProofSearch.GuidanceStatus.INVALID,r.guidance());assertTrue(r.fallbackUsed());checked(r,ps,q);
        }
        var insufficient=GuidedProofSearch.find(fake(hint(q,List.of(ps.getFirst()))),ps,q,32,10000,1);
        assertNotEquals(GuidedProofSearch.GuidanceStatus.USED,insufficient.guidance());assertTrue(insufficient.fallbackUsed());checked(insufficient,ps,q);
    }
    @Test void semanticTautologyIsNotAutomaticallyASyntacticProof(){
        var p=a("p");var q=a("q");var peirce=Formula.implies(Formula.implies(Formula.implies(p,q),p),p);
        assertEquals(ModelExplorer.Status.PROVEN_TRUE,ModelExplorer.analyze(List.of(),peirce,ModelExplorer.Budget.small()).status());
        var r=GuidedProofSearch.find(new LogicNgExplanationService(),List.of(),peirce,128,10000,2);
        assertTrue(r.outcome().proof().isEmpty());assertNotEquals(GuidedProofSearch.GuidanceStatus.USED,r.guidance());
        var theorem=Formula.implies(p,p);var found=GuidedProofSearch.find(new LogicNgExplanationService(),List.of(),theorem,128,10000,2);
        assertEquals(GuidedProofSearch.GuidanceStatus.USED,found.guidance());assertTrue(found.selectedSupportIds().isEmpty());checked(found,List.of(),theorem);
    }
    @Test void oneBudgetIsSharedByHintsAndFallback(){
        var ps=simple();var q=a("q");
        var limited=GuidedProofSearch.find(fake(hint(q,ps)),ps,q,32,1,2);
        assertEquals(ProofSearch.SearchStatus.RESOURCE_LIMIT,limited.outcome().status());assertEquals(1,limited.outcome().operations());
        assertFalse(limited.fallbackUsed());assertTrue(limited.outcome().proof().isEmpty());
        var fallback=GuidedProofSearch.find(fake(hint(q,List.of())),ps,q,32,3,1);
        assertTrue(fallback.outcome().operations()<=3);assertEquals(1,fallback.examinedSupports());
        assertTrue(fallback.outcome().proof().isEmpty());
    }
    @Test void failedAndDisabledProvidersCannotInjectProofs(){
        var ps=simple();var q=a("q");var calls=new AtomicInteger();
        var failed=new SemanticExplanationProvider(){public SemanticExplanation explain(Collection<Premise> p,Formula f){calls.incrementAndGet();throw new IllegalStateException("simulated provider failure");}
            public boolean matches(SemanticExplanation r,Collection<Premise> p,Formula f){throw new AssertionError();}};
        var disabled=GuidedProofSearch.find(failed,ps,q,32,10000,0);assertEquals(0,calls.get());checked(disabled,ps,q);
        var result=GuidedProofSearch.find(failed,ps,q,32,10000,2);assertEquals(1,calls.get());
        assertEquals(GuidedProofSearch.GuidanceStatus.FAILED,result.guidance());checked(result,ps,q);
        var dup=new ArrayList<>(ps);dup.add(ps.getFirst());assertThrows(IllegalArgumentException.class,()->GuidedProofSearch.find(failed,dup,q,32,100,2));
    }
}
