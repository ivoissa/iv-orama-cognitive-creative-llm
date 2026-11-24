package fr.ivorama.cognitive;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
final class P22SearchProfileTest {
    @Test void measurementsDoNotExposeUncheckedProofs() {
        var p=Formula.atom("p");var q=Formula.atom("q");var ps=List.of(p,Formula.implies(p,q));
        var measured=ProofSearch.measureIndexedWithBudget(ps,q,64,10000);
        var ordinary=ProofSearch.findIndexedWithBudget(ps,q,64,10000);
        assertEquals(ordinary,measured.outcome());
        assertTrue(measured.searchNanos()>0);assertTrue(measured.verificationNanos()>0);
        var checked=ProofChecker.verify(measured.outcome().proof().orElseThrow(),ps);
        assertTrue(checked.valid());assertEquals(q,checked.conclusion());
    }
    @Test void exhaustedSearchHasNoVerificationClaim() {
        var p=Formula.atom("p");var q=Formula.atom("q");
        var result=ProofSearch.measureIndexedWithBudget(List.of(p),q,64,1);
        assertEquals(ProofSearch.SearchStatus.RESOURCE_LIMIT,result.outcome().status());
        assertEquals(1,result.outcome().operations());assertEquals(0,result.verificationNanos());
        assertTrue(result.outcome().proof().isEmpty());assertTrue(result.searchNanos()>0);
    }
}
