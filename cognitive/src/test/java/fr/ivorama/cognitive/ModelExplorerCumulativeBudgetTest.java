package fr.ivorama.cognitive;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class ModelExplorerCumulativeBudgetTest {
    @Test void sumOfComponentsIsBoundedBeforeEnumeration() {
        var ps=new ArrayList<Formula>();
        for(int i=0;i<4;i++)ps.add(Formula.implies(Formula.and(Formula.atom("a"+i),Formula.atom("b"+i)),Formula.atom("c"+i)));
        var b=new ModelExplorer.Budget(3,8);var q=Formula.atom("a0");
        assertEquals(ModelExplorer.Status.UNDETERMINED,ModelExplorer.analyze(ps,q,b).status());
        assertEquals(ModelExplorer.Status.UNDETERMINED,ModelExplorer.analyze(ps,q,b,32).status());
        var limited=ModelExplorer.analyze(ps,q,b,31);
        assertEquals(ModelExplorer.Status.RESOURCE_LIMIT,limited.status());
        assertTrue(limited.explanation().contains("visited=24"));
        assertTrue(limited.trueWitness().isEmpty());assertTrue(limited.falseWitness().isEmpty());
    }
    @Test void enoughTotalDoesNotRelaxPerComponentLimit() {
        var q=Formula.and(Formula.atom("a"),Formula.atom("b"));
        assertEquals(ModelExplorer.Status.RESOURCE_LIMIT,
            ModelExplorer.analyze(List.of(),q,new ModelExplorer.Budget(1,2),10000).status());
    }
    @Test void factorizationCanFitWhenFullEnumerationCannot() {
        var q=Formula.atom("a");var extra=List.of(Formula.atom("b"),Formula.atom("c"));
        var b=new ModelExplorer.Budget(3,8);
        assertEquals(ModelExplorer.Status.UNDETERMINED,ModelExplorer.analyze(List.of(),q,extra,b,6).status());
        assertEquals(ModelExplorer.Status.RESOURCE_LIMIT,ModelExplorer.analyzeFull(List.of(),q,extra,b,6).status());
    }
}
