package fr.ivorama.cognitive.agent;
import fr.ivorama.cognitive.HypothesisReasoningEngine.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Reasoned078InvestigationTest {
 @TempDir Path root;
 private ReasonedObserverSession.Probe probe(String s,String file){return new ReasonedObserverSession.Probe(s,file,1,40,s+"=yes",s+"=no");}
 private ReasonedObserverSession.Result run(ReasonedObserverSession.Plan p,InvestigationOptions options)throws Exception{
  return new ReasonedObserverSession(new ReadOnlyWorkspace(root),p,null,options).investigate("Diagnostic ?");
 }
 @Test void commonConstraintCanRefuteEveryHypothesis()throws Exception{
  Files.writeString(root.resolve("s.txt"),"s=no\n");
  var p=new ReasonedObserverSession.Plan("common",true,Map.of("a",Map.of("s",true),"b",Map.of("s",true)),List.of(probe("s","s.txt")));
  var r=run(p,InvestigationOptions.defaults());assertEquals(State.INCONSISTENT,r.finding().state());assertEquals(1,r.report().toolCalls());
 }
 @Test void partialPredictionCanEliminateAnAlternative()throws Exception{
  Files.writeString(root.resolve("s.txt"),"s=no\n");
  var p=new ReasonedObserverSession.Plan("partial",true,Map.of("specific",Map.of("s",true),"other",Map.of()),List.of(probe("s","s.txt")));
  var r=run(p,InvestigationOptions.defaults());assertEquals(State.PROVEN_CONDITIONALLY,r.finding().state());assertEquals("other",r.finding().cause());
 }
 @Test void explicitAuditContinuesAfterAUniqueCandidate()throws Exception{
  Files.writeString(root.resolve("a.txt"),"a=yes\n");Files.writeString(root.resolve("b.txt"),"b=no\n");
  var p=new ReasonedObserverSession.Plan("audit",true,Map.of("h1",Map.of("a",true,"b",true),"h2",Map.of("a",false,"b",false)),List.of(probe("a","a.txt"),probe("b","b.txt")));
  var d=run(p,InvestigationOptions.defaults());assertEquals(State.PROVEN_CONDITIONALLY,d.finding().state());assertEquals(1,d.report().toolCalls());
  var a=run(p,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true));
  assertEquals(State.INCONSISTENT,a.finding().state());assertEquals(2,a.report().toolCalls());assertTrue(a.completion().complete());
 }
 @Test void laterPageSuppliesEvidenceInsteadOfInventingAbsence()throws Exception{
  Files.writeString(root.resolve("s.txt"),"neutral\n".repeat(44)+"s=yes\n");
  var p=new ReasonedObserverSession.Plan("paging",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),List.of(probe("s","s.txt")));
  var first=new ReadOnlyWorkspace(root).read("s.txt",1,40);assertEquals(40,first.lastCompleteLine());assertEquals(41,first.nextLine());assertTrue(first.truncated());
  var r=run(p,InvestigationOptions.defaults());assertEquals(State.PROVEN_CONDITIONALLY,r.finding().state());assertEquals("a",r.finding().cause());
  assertEquals(List.of("UNKNOWN","TRUE"),r.steps().stream().map(ReasonedObserverSession.Step::result).toList());assertEquals(2,r.report().toolCalls());
 }
 @Test void lateContradictionAndDuplicatePositiveAcrossPagesAreSafe()throws Exception{
  Files.writeString(root.resolve("s.txt"),"s=yes\n"+"neutral\n".repeat(40)+"s=yes\ns=no\n");
  var p=new ReasonedObserverSession.Plan("late",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),List.of(probe("s","s.txt")));
  var r=run(p,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true));
  assertEquals(State.INCONSISTENT,r.finding().state());assertEquals(2,r.facts().size());assertTrue(r.completion().complete());
 }
 @Test void auditDoesNotClaimCompletionWhenToolBudgetEnds()throws Exception{
  var probes=new ArrayList<ReasonedObserverSession.Probe>();Map<String,Boolean> a=new LinkedHashMap<>(),b=new LinkedHashMap<>();
  for(int i=1;i<=6;i++){String s="s"+i;Files.writeString(root.resolve(s+".txt"),s+"=yes\n");probes.add(probe(s,s+".txt"));a.put(s,true);b.put(s,false);}
  var p=new ReasonedObserverSession.Plan("budget",true,Map.of("a",a,"b",b),probes);
  var r=run(p,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true));
  assertEquals(5,r.report().toolCalls());assertFalse(r.completion().complete());assertTrue(r.report().evidenceLimitReached());assertEquals(1,r.completion().pendingSignals().size());
 }
 @Test void canonicalReadPathKeepsExactWitnessesBoundToTheFile()throws Exception{
  Files.writeString(root.resolve("s.txt"),"s=yes\n");
  var p=new ReasonedObserverSession.Plan("alias",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),List.of(probe("s","./s.txt")));
  var r=run(p,InvestigationOptions.defaults());assertEquals("a",r.finding().cause());
 }
 @Test void onlyRegisteredWitnessesReachTheCommentaryView()throws Exception{
  Files.writeString(root.resolve("s.txt"),"s=yes_extra\n// s=yes\n");
  var p=new ReasonedObserverSession.Plan("view",true,Map.of("a",Map.of("s",true),"b",Map.of("s",false)),List.of(probe("s","s.txt")));
  var r=ReasonedObserverSession.grounded(new ReadOnlyWorkspace(root),p,InvestigationOptions.defaults(),(q,f,e,facts,c)->{
   var view=ReasonedObserverSession.commentaryEvidence(e,facts);assertTrue(facts.isEmpty());assertTrue(view.getFirst().content().startsWith("UNKNOWN"));
   assertFalse(view.getFirst().content().contains("yes"));return Optional.of("Aucune mesure reconnue.");
  }).investigate("Que peut-on conclure ?");
  assertEquals(State.UNDETERMINED,r.finding().state());assertTrue(r.report().evidence().getFirst().content().contains("yes_extra"));
 }
 @Test void pagingNeverTurnsGlobalClippingIntoARecognizedFact()throws Exception{
  String padding="x".repeat(731)+"\n"+"x".repeat(731)+"\n"+"x".repeat(731)+"\n"+"x".repeat(758)+"\n";
  Files.writeString(root.resolve("a.txt"),padding);Files.writeString(root.resolve("b.txt"),padding);Files.writeString(root.resolve("c.txt"),"s3=yes_extra\n");
  var p=new ReasonedObserverSession.Plan("clipped",true,Map.of("a",Map.of("s1",true,"s2",true,"s3",true),"b",Map.of("s1",false,"s2",false,"s3",false)),
   List.of(probe("s1","a.txt"),probe("s2","b.txt"),probe("s3","c.txt")));
  var r=run(p,InvestigationOptions.defaults());assertEquals(State.UNDETERMINED,r.finding().state());assertTrue(r.facts().isEmpty());
  assertTrue(r.report().evidence().stream().mapToInt(e->e.content().length()).sum()<=6000);assertFalse(r.completion().complete());
 }
 @Test void unavailableDiagnosticsAlsoRespectTheCharacterBudget()throws Exception{
  String padding="x".repeat(731)+"\n"+"x".repeat(731)+"\n"+"x".repeat(731)+"\n"+"x".repeat(758)+"\n";
  Files.writeString(root.resolve("a.txt"),padding);Files.writeString(root.resolve("b.txt"),padding);
  var p=new ReasonedObserverSession.Plan("unavailable_budget",true,Map.of("a",Map.of("s1",true,"s2",true,"s3",true),"b",Map.of("s1",false,"s2",false,"s3",false)),
   List.of(probe("s1","a.txt"),probe("s2","b.txt"),probe("s3","missing.txt")));
  var r=run(p,new InvestigationOptions(InvestigationOptions.Goal.DIAGNOSE,true,false));
  assertEquals(6000,r.report().evidence().stream().mapToInt(e->e.content().length()).sum());assertTrue(r.facts().isEmpty());
  assertTrue(r.report().evidenceLimitReached());assertFalse(r.completion().complete());
 }
}
