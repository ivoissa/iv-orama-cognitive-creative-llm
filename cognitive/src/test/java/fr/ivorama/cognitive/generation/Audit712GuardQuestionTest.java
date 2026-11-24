package fr.ivorama.cognitive.generation;
import fr.ivorama.cognitive.agent.*;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class Audit712GuardQuestionTest {
 @TempDir Path root;
 ReasonedObserverSession.Plan plan(){return new ReasonedObserverSession.Plan("questions",true,Map.of("alpha",Map.of("a",true),"beta",Map.of("a",false)),List.of(new ReasonedObserverSession.Probe("a","a.txt",1,40,"a=yes","a=no")));}
 ReasonedObserverSession.Result result()throws Exception{Files.writeString(root.resolve("a.txt"),"a=yes\n");return new ReasonedObserverSession(new ReadOnlyWorkspace(root),plan(),null).investigate("Diagnostic ?");}
 @Test void neverRewritesQuestionsAsIfTheyAssertedAFalseCause()throws Exception{
  var r=result();for(String draft:List.of("La cause est beta?","La seule hypothèse compatible est alpha?","Impossible de conclure?","L'audit est complet?","Le contrôle est complet car tout est lu?")){
   var review=ReasonedGenerativeGuard.review(plan(),r,draft);assertEquals(draft,review.suggestion());assertTrue(review.edits().isEmpty());assertEquals(ReasonedGenerativeGuard.Status.NOT_VERIFIABLE,review.status());
  }
 }
 @Test void unrelatedTextIsPreservedAndNotPresentedAsChecked()throws Exception{
  String draft="Les arbres couvrent le sentier.";var review=ReasonedGenerativeGuard.review(plan(),result(),draft);assertEquals(draft,review.suggestion());assertEquals(ReasonedGenerativeGuard.Status.NOT_VERIFIABLE,review.status());
 }
 @Test void stillRepairsARecognizedFalseDeclarativeSentence()throws Exception{
  var review=ReasonedGenerativeGuard.review(plan(),result(),"La seule hypothèse compatible est beta.");assertEquals(1,review.edits().size());assertTrue(review.suggestion().contains("alpha"));
 }
}
