package fr.ivorama.cognitive;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LogicNgUnsatCoreTest {
    static SemanticExplanation.Premise p(String id,Formula f){return new SemanticExplanation.Premise(id,f,"source-"+id,"excerpt-"+id);}
    @Test void realLibraryCoreCarriesSourceIdentityAndIsIndependentlyVerified(){
        Formula a=Formula.atom("a"),b=Formula.atom("b");
        var ps=List.of(p("p1",a),p("p2",Formula.not(a)),p("noise",b));
        var result=new LogicNgExplanationService().conflict(ps);
        assertEquals(SemanticExplanation.Status.COMPLETE,result.status());
        var core=result.rawCore().orElseThrow();assertTrue(core.independentlyVerified());assertFalse(core.subsetMinimal());
        assertEquals(Set.of("p1","p2"),new HashSet<>(core.ids()));
        core.premises().forEach(x->assertTrue(ps.contains(x)));
        assertFalse(result.syntacticProofCertificate());assertFalse(result.empiricallyVerified());
        assertFalse(result.alternativesComplete());assertTrue(result.describe().contains("source-p1"));
    }
    @Test void tautologySatisfiableBottomAndDuplicateFormulaSources(){
        Formula a=Formula.atom("a");var service=new LogicNgExplanationService();
        assertTrue(service.conflict(List.of(p("ok",Formula.implies(a,a)))).rawCore().isEmpty());
        assertEquals(List.of("bottom"),service.conflict(List.of(p("bottom",Formula.Bottom.INSTANCE))).rawCore().orElseThrow().ids());
        var result=service.conflict(List.of(p("first",a),p("second",a),p("neg",Formula.not(a))));
        assertTrue(result.rawCore().orElseThrow().independentlyVerified());
        assertTrue(result.rawCore().orElseThrow().ids().contains("neg"));
        assertThrows(IllegalArgumentException.class,()->service.conflict(List.of(p("same",a),p("same",Formula.not(a)))));
    }
    @Test void exhaustedVerificationCannotClaimVerificationOrMinimality(){
        var limits=new LogicNgExplanationService.Limits(64,20000,128,20,10,10,100,0,1,2000);
        var result=new LogicNgExplanationService(limits).conflict(List.of(p("a",Formula.atom("a")),p("na",Formula.not(Formula.atom("a")))));
        assertEquals(SemanticExplanation.Status.VERIFICATION_LIMIT,result.status());
        assertFalse(result.rawCore().orElseThrow().independentlyVerified());assertTrue(result.mus().isEmpty());
        assertFalse(result.syntacticProofCertificate());
    }
}
