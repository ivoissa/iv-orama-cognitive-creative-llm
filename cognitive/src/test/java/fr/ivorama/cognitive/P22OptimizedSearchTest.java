package fr.ivorama.cognitive;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class P22OptimizedSearchTest {
    private static Formula a(String n){return Formula.atom(n);}
    private static List<Formula> chain(int n,boolean reverse){
        List<Formula> ps=new ArrayList<>();for(int i=0;i<n;i++)ps.add(Formula.implies(a("n"+i),a("n"+(i+1))));
        if(reverse)Collections.reverse(ps);ps.add(a("n0"));return ps;
    }
    @Test void reshapedLongTermsKeepExactGoalAndDependencies() {
        for(boolean reverse:List.of(false,true))for(int n:new int[]{31,32,33,64,80,128}) {
            var ps=chain(n,reverse);var goal=a("n"+n);
            var result=ProofSearch.findIndexedWithBudget(ps,goal,512,100000);
            var checked=ProofChecker.verify(result.proof().orElseThrow(),ps);
            assertTrue(checked.valid());assertEquals(goal,checked.conclusion());assertEquals(Set.copyOf(ps),checked.dependencies());
        }
    }
    @Test void optionalReshapingDoesNotLoseAnAlreadyFoundProof() {
        var ps=chain(80,true);
        for(long max:new long[]{850,881,900,1000,1200,1500}) {
            var result=ProofSearch.findIndexedWithBudget(ps,a("n80"),512,max);
            assertEquals(ProofSearch.SearchStatus.FOUND,result.status(),"budget="+max);
            assertTrue(result.operations()<=max);
            assertTrue(ProofChecker.verify(result.proof().orElseThrow(),ps).valid());
        }
    }
    @Test void temporaryCutsDoNotAuthorizeMissingPremises() {
        var ps=chain(64,true);var result=ProofSearch.findIndexedWithBudget(ps,a("n64"),512,10000);
        var retracted=new ArrayList<>(ps);retracted.remove(17);
        assertFalse(ProofChecker.verify(result.proof().orElseThrow(),retracted).valid());
        assertTrue(ProofSearch.findIndexedWithBudget(retracted,a("n64"),512,10000).proof().isEmpty());
    }
    @Test void sharedGoalsAndImplicationScopesRemainIsolated() {
        var ps=chain(64,true);var q=a("n64");var goal=Formula.and(q,q);
        var result=ProofSearch.findIndexedWithBudget(ps,goal,512,10000);
        var checked=ProofChecker.verify(result.proof().orElseThrow(),ps);assertTrue(checked.valid());assertEquals(goal,checked.conclusion());
        var poison=Formula.and(Formula.implies(q,q),q);
        assertTrue(ProofSearch.findIndexedWithBudget(List.of(),poison,512,10000).proof().isEmpty());
    }
    @Test void singlePremiseFastPathPreservesExactBudget() {
        var p=a("p");assertEquals(ProofSearch.SearchStatus.RESOURCE_LIMIT,
            ProofSearch.findIndexedWithBudget(List.of(p),p,1,1).status());
        var found=ProofSearch.findIndexedWithBudget(List.of(p),p,1,2);
        assertEquals(2,found.operations());assertTrue(ProofChecker.verify(found.proof().orElseThrow(),List.of(p)).valid());
    }
}
