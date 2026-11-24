package fr.ivorama.cognitive.generative;
import fr.ivorama.cognitive.agent.ObserverSession;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Reasoned077CommentTest {
 private final List<ObserverSession.Evidence> evidence=List.of(new ObserverSession.Evidence("F1","READ","tls.txt","tls.txt:1: tls=yes\ntls.txt:2: tls=no\n",1,false));
 @Test void generatedReferenceDoesNotReconstructMultilineQuotes(){
  String out=ReasonedCommentFormat.validateAndRender("{\"text\":\"Les relevés se contredisent ; il faut suspendre le diagnostic.\",\"evidenceIds\":[\"F1\"]}",evidence);
  assertTrue(out.contains("[F1]"));assertTrue(out.contains("sens non certifié"));
 }
 @Test void unfinishedObservedSuffixAndUnknownReferencesAreRejected(){
  assertThrows(IllegalArgumentException.class,()->ReasonedCommentFormat.validateAndRender("{\"text\":\"Le problème au niveau\",\"evidenceIds\":[\"F1\"]}",evidence));
  assertThrows(IllegalArgumentException.class,()->ReasonedCommentFormat.validateAndRender("{\"text\":\"Une phrase.\",\"evidenceIds\":[\"F99\"]}",evidence));
  assertThrows(IllegalArgumentException.class,()->ReasonedCommentFormat.validateAndRender("{\"text\":\"Une phrase.\",\"evidenceIds\":[\"F1\",\"F1\"]}",evidence));
 }
 @Test void duplicateFieldsAndSmuggledCitationsAreRejected(){
  assertThrows(IllegalArgumentException.class,()->ReasonedCommentFormat.validateAndRender("{\"text\":\"A.\",\"text\":\"B.\",\"evidenceIds\":[]}",evidence));
  assertThrows(IllegalArgumentException.class,()->ReasonedCommentFormat.validateAndRender("{\"text\":\"Inventé [F99].\",\"evidenceIds\":[]}",evidence));
 }
}
