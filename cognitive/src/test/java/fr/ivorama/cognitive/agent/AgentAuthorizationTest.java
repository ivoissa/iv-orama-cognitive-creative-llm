package fr.ivorama.cognitive.agent;
import fr.ivorama.cognitive.security.*;import fr.ivorama.cognitive.security.LlmSecurityPolicy.Mode;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentAuthorizationTest {
 @TempDir Path root;
 static AgentAuthorizationPolicy policy(Mode mode,String scope,boolean read,boolean complete){return new AgentAuthorizationPolicy(mode,scope,Set.of(ObserverSession.Operation.values()),true,true,read,complete);}
 static AgentAuthorizationGate gate(Mode mode,String scope,boolean read,boolean complete){return new AgentAuthorizationGate(policy(mode,scope,read,complete),e->{});}
 void file(String path,String content)throws Exception{Path p=root.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,content);}
 static ObserverSession.Action read(String path){return new ObserverSession.Action(ObserverSession.Operation.READ,path,"",1,20);}
 static ObserverSession.Action finish(){return new ObserverSession.Action(ObserverSession.Operation.FINISH,"","",1,20);}
 ReasonedObserverSession.Plan plan(String path){return new ReasonedObserverSession.Plan("trusted",true,Map.of("alpha",Map.of("ready",true),"beta",Map.of("ready",false)),List.of(new ReasonedObserverSession.Probe("ready",path,1,20,"ready=yes","ready=no")));}
 ReasonedObserverSession.Result result(String text)throws Exception{file("public/data.txt",text);return ReasonedObserverSession.authorized(new ReadOnlyWorkspace(root),plan("public/data.txt"),null,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true),gate(Mode.OFF,".",false,false)).investigate("Audit ?");}
 AgentDecisionVerifier.Proposal claim(String cause,boolean complete,String... ids){return new AgentDecisionVerifier.Proposal(AgentDecisionVerifier.Kind.CAUSE,cause,List.of(ids),complete);}
 @Test void readOutsideScopeStopsBeforeAccessOrSynthesis()throws Exception{
  var synthesized=new AtomicInteger();var session=new ObserverSession(new ReadOnlyWorkspace(root),(q,e)->read("foreign/absent.txt"),(q,e)->{synthesized.incrementAndGet();return "answer";},false,gate(Mode.ENFORCE,"public",false,false));
  var error=assertThrows(AgentAuthorizationGate.Blocked.class,()->session.investigate("Read"));assertTrue(error.getMessage().contains("OUTSIDE_SCOPE"));assertFalse(error.getMessage().contains("foreign"));assertEquals(0,synthesized.get());
 }
 @Test void scopedBootstrapAndSearchNeverReturnForeignEvidence()throws Exception{
  file("public/A.java","TARGET public\n");file("foreign/B.java","TARGET OUTSIDE_MARKER\n");var step=new AtomicInteger();
  var session=new ObserverSession(new ReadOnlyWorkspace(root),(q,e)->step.getAndIncrement()==0?new ObserverSession.Action(ObserverSession.Operation.SEARCH,"","TARGET",1,20):finish(),(q,e)->e.toString(),true,gate(Mode.ENFORCE,"public",false,false));var r=session.investigate("Inspect");assertEquals(2,r.toolCalls());assertTrue(r.answer().contains("public/A.java"));assertFalse(r.answer().contains("OUTSIDE_MARKER"));assertFalse(r.answer().contains("foreign"));
 }
 @Test void auditKeepsExistingReportEvenWhenReadWouldBeDenied()throws Exception{
  file("foreign/B.java","outside content\n");var reports=new ArrayList<ObserverSession.Report>();var events=new ArrayList<AgentAuthorizationGate.Event>();
  for(Mode mode:List.of(Mode.OFF,Mode.AUDIT)){var n=new AtomicInteger();var auth=new AgentAuthorizationGate(policy(mode,"public",false,false),events::add);reports.add(new ObserverSession(new ReadOnlyWorkspace(root),(q,e)->n.getAndIncrement()==0?read("foreign/B.java"):finish(),(q,e)->e.toString(),false,auth).investigate("Inspect"));}
  assertEquals(reports.getFirst(),reports.getLast());assertTrue(events.stream().anyMatch(e->e.status()==AgentAuthorizationGate.Status.DENY));assertFalse(events.toString().contains("foreign"));
 }
 @Test void scopeUsesSegmentsRatherThanTextPrefix(){
  var p=policy(Mode.ENFORCE,"src/main",false,false);assertTrue(p.permitsPath("./src//main/X.java"));assertTrue(p.permitsPath("src\\main\\X.java"));assertFalse(p.permitsPath("src/main-old/X.java"));
  for(String path:List.of("../src/main/X.java","/src/main/X.java","C:\\src\\main\\X.java","src/.git/X.java"))assertThrows(IllegalArgumentException.class,()->p.permitsPath(path));
 }
 @Test void operationRequiresExplicitPermission()throws Exception{
  var policy=new AgentAuthorizationPolicy(Mode.ENFORCE,".",Set.of(ObserverSession.Operation.READ),true,false,false,false);var session=new ObserverSession(new ReadOnlyWorkspace(root),(q,e)->new ObserverSession.Action(ObserverSession.Operation.SEARCH,"","term",1,20),(q,e)->{fail();return "x";},false,new AgentAuthorizationGate(policy,e->{}));assertThrows(AgentAuthorizationGate.Blocked.class,()->session.investigate("Inspect"));
 }
 @Test void earlyFinishWithoutRequiredReadNeedsEvidence()throws Exception{
  var session=new ObserverSession(new ReadOnlyWorkspace(root),(q,e)->finish(),(q,e)->{fail();return "x";},false,gate(Mode.ENFORCE,".",true,false));assertTrue(assertThrows(AgentAuthorizationGate.Blocked.class,()->session.investigate("Inspect")).getMessage().contains("NEEDS_EVIDENCE"));
 }
 @Test void exhaustionCannotBypassTheReadRequirement()throws Exception{
  var calls=new AtomicInteger();var session=new ObserverSession(new ReadOnlyWorkspace(root),(q,e)->new ObserverSession.Action(ObserverSession.Operation.SEARCH,"","absent"+calls.getAndIncrement(),1,20),(q,e)->{fail();return "x";},false,gate(Mode.ENFORCE,".",true,false));assertThrows(AgentAuthorizationGate.Blocked.class,()->session.investigate("Inspect"));assertEquals(5,calls.get());
 }
 @Test void reasonedReadsAreControlledBeforeCommentary()throws Exception{
  var comments=new AtomicInteger();var session=ReasonedObserverSession.authorized(new ReadOnlyWorkspace(root),plan("foreign/data.txt"),(q,f,e)->{comments.incrementAndGet();return "text";},new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true),gate(Mode.ENFORCE,"public",false,false));assertThrows(AgentAuthorizationGate.Blocked.class,()->session.investigate("Audit ?"));assertEquals(0,comments.get());
 }
 @Test void incompleteAuditDoesNotAuthorizeCompleteAuditAndSkipsCommentary()throws Exception{
  file("public/data.txt","ready=yes\n"+"x\n".repeat(200));var comments=new AtomicInteger();var session=ReasonedObserverSession.authorized(new ReadOnlyWorkspace(root),plan("public/data.txt"),(q,f,e)->{comments.incrementAndGet();return "text";},new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true),gate(Mode.ENFORCE,"public",false,true));assertThrows(AgentAuthorizationGate.Blocked.class,()->session.investigate("Audit ?"));assertEquals(0,comments.get());
 }
 @Test void sameIncompleteFactsCanSupportConditionalReport()throws Exception{
  file("public/data.txt","ready=yes\n"+"x\n".repeat(200));var r=ReasonedObserverSession.authorized(new ReadOnlyWorkspace(root),plan("public/data.txt"),null,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true),gate(Mode.ENFORCE,"public",false,false)).investigate("Audit ?");assertEquals("alpha",r.finding().cause());assertFalse(r.completion().complete());
 }
 @Test void fullAuditWithEvidencePermitsCommentary()throws Exception{
  file("public/data.txt","ready=yes\n");var comments=new AtomicInteger();var r=ReasonedObserverSession.authorized(new ReadOnlyWorkspace(root),plan("public/data.txt"),(q,f,e)->{comments.incrementAndGet();return "text";},new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true),gate(Mode.ENFORCE,"public",false,true)).investigate("Audit ?");assertTrue(r.completion().complete());assertEquals(1,comments.get());
 }
 @Test void wrongCauseAndForeignCitationsAreRejected()throws Exception{
  var r=result("ready=yes\n");var auth=gate(Mode.ENFORCE,"public",false,false);
  assertEquals(AgentAuthorizationGate.Status.DENY,auth.evaluateDecision(plan("public/data.txt"),r,claim("beta",false,"F1"),AgentAuthorizationGate.Purpose.CONDITIONAL_REPORT).status());
  assertEquals(AgentAuthorizationGate.Status.DENY,auth.evaluateDecision(plan("public/data.txt"),r,claim("alpha",false,"F99"),AgentAuthorizationGate.Purpose.CONDITIONAL_REPORT).status());
 }
 @Test void lackOfSupportingProofNeedsEvidence()throws Exception{
  var r=result("ready=yes\n");assertEquals(AgentAuthorizationGate.Status.NEEDS_EVIDENCE,gate(Mode.ENFORCE,"public",false,false).evaluateDecision(plan("public/data.txt"),r,claim("alpha",false),AgentAuthorizationGate.Purpose.CONDITIONAL_REPORT).status());
 }
 @Test void forgedCompletionFlagDoesNotCreateAuthority()throws Exception{
  var r=result("ready=yes\n"+"x\n".repeat(200));var fake=new ReasonedObserverSession.Result(r.report(),r.finding(),r.steps(),r.facts(),r.commentaryError(),new ReasonedObserverSession.Completion(InvestigationOptions.Goal.AUDIT_ALL,true,List.of(),List.of(),List.of()));
  assertNotEquals(AgentAuthorizationGate.Status.ALLOW,gate(Mode.ENFORCE,"public",false,true).evaluateDecision(plan("public/data.txt"),fake,claim("alpha",true,"F1"),AgentAuthorizationGate.Purpose.COMPLETE_AUDIT).status());
 }
 @Test void proofDoesNotOverrideCallerPermissions()throws Exception{
  var r=result("ready=yes\n");var p=new AgentAuthorizationPolicy(Mode.ENFORCE,"public",Set.of(ObserverSession.Operation.values()),false,false,false,false);assertEquals(AgentAuthorizationGate.Status.DENY,new AgentAuthorizationGate(p,e->{}).evaluateDecision(plan("public/data.txt"),r,claim("alpha",false,"F1"),AgentAuthorizationGate.Purpose.CONDITIONAL_REPORT).status());
 }
 @Test void offDoesNotPretendToCertifyDecisions(){
  var verdict=gate(Mode.OFF,".",false,false).evaluateDecision(null,null,null,null);assertEquals(AgentAuthorizationGate.Status.BYPASSED,verdict.status());
 }
 @Test void malformedDecisionNeverCreatesAnAuthorization(){
  var verdict=gate(Mode.ENFORCE,".",false,false).evaluateDecision(null,null,null,null);assertEquals(AgentAuthorizationGate.Status.DENY,verdict.status());assertEquals(List.of(AgentAuthorizationGate.Reason.INTEGRITY_ERROR),verdict.reasons());
 }
 @Test void telemetryFailureDoesNotBypassEnforcement(){
  var auth=new AgentAuthorizationGate(policy(Mode.ENFORCE,"public",false,false),e->{throw new IllegalStateException("sensitive");});var error=assertThrows(AgentAuthorizationGate.Blocked.class,()->auth.beforeAction(read("foreign/X.java"),List.of()));assertTrue(error.getMessage().contains("CONTROL_FAILURE"));assertNull(error.getCause());
 }
 @Test void trustedConfigurationIsStrictAndOffDoesNotReadIt()throws Exception{
  Path file=root.resolve("policy.json");Files.writeString(file,"{\"readScope\":\"public\",\"operations\":[\"READ\"],\"allowConditionalReport\":true}");withProperties(Map.of("ivorama.agent.authorizationMode","ENFORCE","ivorama.agent.authorizationPolicyFile",file.toString()),()->{var p=AgentAuthorizationPolicy.fromSystemProperties();assertEquals("public",p.readScope());assertEquals(Set.of(ObserverSession.Operation.READ),p.operations());});
  Files.writeString(file,"{\"allowCompleteAudit\":\"true\"}");withProperties(Map.of("ivorama.agent.authorizationMode","ENFORCE","ivorama.agent.authorizationPolicyFile",file.toString()),()->assertThrows(IllegalArgumentException.class,AgentAuthorizationPolicy::fromSystemProperties));
  withProperties(Map.of("ivorama.agent.authorizationMode","OFF","ivorama.agent.authorizationPolicyFile","/absent"),()->assertEquals(Mode.OFF,AgentAuthorizationPolicy.fromSystemProperties().mode()));
 }
 static void withProperties(Map<String,String> props,Runnable action){var old=new HashMap<String,String>();props.forEach((k,v)->{old.put(k,System.getProperty(k));System.setProperty(k,v);});try{action.run();}finally{old.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}}
}
