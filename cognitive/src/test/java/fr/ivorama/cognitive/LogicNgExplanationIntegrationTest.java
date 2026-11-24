package fr.ivorama.cognitive;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LogicNgExplanationIntegrationTest {
    static IntegratedQuestionCycle enabled(LogicNgExplanationService service){return new IntegratedQuestionCycle(ModelExplorer.Budget.small(),new ModelExplorerBackend(),service);}
    @Test void liveRetractionAndSourceAlternativesDoNotAlterFciOrProvenance(){
        var cycle=enabled(new LogicNgExplanationService());Formula a=Formula.atom("a");
        String first=cycle.teach(a,"first-declaration"),second=cycle.teach(a,"second-declaration");
        var answer=cycle.ask(a);int acts=cycle.questionActCount();
        var explanation=cycle.explainCurrent(a).orElseThrow();assertEquals(2,explanation.supports().size());
        assertEquals(acts,cycle.questionActCount());assertTrue(cycle.isExplanationCurrent(a,explanation));
        assertEquals(IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,answer.status());
        assertNotEquals(IntegratedQuestionCycle.Support.FORMAL_THEOREM,answer.support());
        cycle.retract(first);assertFalse(cycle.isExplanationCurrent(a,explanation));
        var current=cycle.explainCurrent(a).orElseThrow();assertEquals(List.of(second),current.supports().getFirst().ids());
        assertEquals(Optional.of(true),cycle.question(answer.id()).answer());
        cycle.retract(second);assertFalse(cycle.isExplanationCurrent(a,current));assertTrue(cycle.question(answer.id()).reopened());
        assertEquals(SemanticExplanation.Status.NOT_APPLICABLE,cycle.explainCurrent(a).orElseThrow().status());
        assertTrue(new IntegratedQuestionCycle().explainCurrent(a).isEmpty());
    }
    @Test void structuredOutputsPreserveVerdictsProofsAndExposeDeclaredSources()throws Exception{
        for(String name:List.of("01-deduction","02-indetermination","03-contradiction","04-negation","05-incomplete")){
            String json=Files.readString(Path.of("examples/structured/"+name+".json"));
            var before=new StructuredReasoningService(new IntegratedQuestionCycle()).askJson(json);
            var after=new StructuredReasoningService(enabled(new LogicNgExplanationService())).askJson(json);
            assertEquals(before.status(),after.status());assertEquals(before.checkedProof(),after.checkedProof());
            assertEquals(before.conflictPremises(),after.conflictPremises());assertEquals(before.investigation(),after.investigation());
            assertTrue(before.semanticExplanation().isEmpty());
            if(after.analysis().isPresent()){
                var explanation=after.semanticExplanation().orElseThrow();assertFalse(explanation.syntacticProofCertificate());
                if(explanation.rawCore().isPresent()){
                    explanation.rawCore().get().premises().forEach(p->{assertFalse(p.source().isBlank());assertTrue(after.describe().contains(p.id()));});
                }
            }else assertTrue(after.semanticExplanation().isEmpty());
        }
    }
    @Test void exhaustedExplanationBudgetCannotChangeAnAnswerOrAProof()throws Exception{
        var limits=new LogicNgExplanationService.Limits(64,20000,128,1,100,16,100,12,1_000_000,2000);
        String json=Files.readString(Path.of("examples/structured/01-deduction.json"));
        var before=new StructuredReasoningService(new IntegratedQuestionCycle()).askJson(json);
        var after=new StructuredReasoningService(enabled(new LogicNgExplanationService(limits))).askJson(json);
        assertEquals(before.status(),after.status());assertEquals(before.checkedProof(),after.checkedProof());
        assertEquals(SemanticExplanation.Status.RESOURCE_LIMIT,after.semanticExplanation().orElseThrow().status());
        assertTrue(after.describe().contains("RESOURCE_LIMIT"));assertFalse(after.semanticExplanation().get().alternativesComplete());
    }
    @Test void rdoObservationsAndUnknownsAreNotReplacedBySupports(){
        var cycle=enabled(new LogicNgExplanationService());Formula a=Formula.atom("a");cycle.registerObservable(a,1);
        var report=cycle.ask(a);var exp=cycle.explainCurrent(a).orElseThrow();
        assertEquals(IntegratedQuestionCycle.Status.NEED_INFORMATION,report.status());assertTrue(report.answer().isEmpty());
        assertEquals(SemanticExplanation.Status.NOT_APPLICABLE,exp.status());assertTrue(exp.supports().isEmpty());
        assertEquals(Optional.of(a),report.requestedObservation());
    }
}
