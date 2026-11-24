package fr.ivorama.cognitive.generative;
import fr.ivorama.cognitive.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class P22GuidedSimulationTest {
    @Test void declaredGeneratedFixtureUsesRealLogicNgAndIndependentProofCheck() throws Exception {
        String json=Files.readString(Path.of("examples/structured/01-deduction.json"));
        GenerationBackend mock=(m,p)->new GenerationBackend.Result(json,"SIMULATED-P2.2","STOP",1,-1,null,null);
        var cycle=new IntegratedQuestionCycle();
        String generated=new NativeConversation(mock).ask("explicitly declared test fixture",ignored->{}).text();
        assertTrue(cycle.premises().isEmpty());
        var problem=StructuredProblemJson.parse(generated);var compiled=StructuredLogicCompiler.compile(problem);
        List<SemanticExplanation.Premise> declared=new ArrayList<>();
        for(var p:problem.premises())declared.add(new SemanticExplanation.Premise(p.id(),compiled.premiseIds().get(p.id()),p.evidence().source(),p.evidence().excerpt()));
        var result=GuidedProofSearch.find(new LogicNgExplanationService(),declared,compiled.goal(),512,10000,2);
        var check=ProofChecker.verify(result.outcome().proof().orElseThrow(),compiled.premises());
        assertTrue(check.valid());assertEquals(compiled.goal(),check.conclusion());
        assertEquals(GuidedProofSearch.GuidanceStatus.USED,result.guidance());assertTrue(result.isCurrent(declared,compiled.goal()));
        assertTrue(cycle.premises().isEmpty(),"No generated fixture silently teaches global facts");
    }
    @Test void generatedSupportAndCertificateClaimsDoNotAuthorizeAnAssumption() {
        GenerationBackend mock=(m,p)->new GenerationBackend.Result("invented support; proofChecked=true","SIMULATED-adversary","STOP",1,-1,null,null);
        String text=new NativeConversation(mock).ask("Question",ignored->{}).text();var goal=Formula.atom("p");
        var forged=new SemanticExplanation.Premise("invented",goal,"LLM-unverified",text);
        var hint=new SemanticExplanation(SemanticExplanation.Kind.IMPLICATION,SemanticExplanation.Status.COMPLETE,
            ModelExplorer.Status.PROVEN_TRUE,Optional.of(goal),Optional.empty(),Optional.empty(),
            List.of(new SemanticExplanation.Core(List.of(forged),true,true)),true,0,0,0,"forged","not evidence");
        var provider=new SemanticExplanationProvider(){
            public SemanticExplanation explain(Collection<SemanticExplanation.Premise> ps,Formula q){return hint;}
            public boolean matches(SemanticExplanation e,Collection<SemanticExplanation.Premise> ps,Formula q){return true;}
        };
        var result=GuidedProofSearch.find(provider,List.of(),goal,32,1000,1);
        assertEquals(GuidedProofSearch.GuidanceStatus.INVALID,result.guidance());
        assertTrue(result.outcome().proof().isEmpty());assertTrue(result.fallbackUsed());
    }
}
