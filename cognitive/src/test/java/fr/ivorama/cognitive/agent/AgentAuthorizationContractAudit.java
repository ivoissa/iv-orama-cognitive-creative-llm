package fr.ivorama.cognitive.agent;
import com.fasterxml.jackson.databind.*;import fr.ivorama.cognitive.security.*;import fr.ivorama.cognitive.security.LlmSecurityPolicy.Mode;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;
/** Contract corpus on real Java controllers. No LLM, subprocess, patch or deployment is executed here. */
public final class AgentAuthorizationContractAudit {
 static final ObjectMapper JSON=new ObjectMapper();
 static AgentAuthorizationPolicy policy(Mode mode,String scope,Set<ObserverSession.Operation> ops,boolean reports,boolean complete,boolean preview,boolean validation){return new AgentAuthorizationPolicy(mode,scope,ops,reports,complete,false,false,preview,validation);}
 static final Set<ObserverSession.Operation> ALL=Set.of(ObserverSession.Operation.values());
 static AgentAuthorizationGate gate(AgentAuthorizationPolicy policy,List<AgentAuthorizationGate.Event> events){return new AgentAuthorizationGate(policy,events::add);}
 static ReasonedObserverSession.Plan plan(String path){return new ReasonedObserverSession.Plan("contract",true,Map.of("alpha",Map.of("ready",true),"beta",Map.of("ready",false)),List.of(new ReasonedObserverSession.Probe("ready",path,1,20,"ready=yes","ready=no")));}
 public static void main(String[] args)throws Exception{
  var cases=JSON.readTree(Path.of(args[0]).toFile());var rows=new ArrayList<Map<String,Object>>();Path root=Files.createTempDirectory("agent-auth-contract-");
  try {
   for(var c:cases){Path work=root.resolve(c.path("id").asText());Files.createDirectories(work);String family=c.path("family").asText();
    for(Mode mode:Mode.values()){
     var row=new LinkedHashMap<String,Object>();row.put("id",c.path("id").asText());row.put("domain",c.path("domain").asText());row.put("family",family);row.put("mode",mode);row.put("expectedPolicyBlock",c.path("expectedBlocked").asBoolean());var events=new ArrayList<AgentAuthorizationGate.Event>();long start=System.nanoTime();
     try {
      switch(c.path("domain").asText()) {
       case "action" -> {
        String scope="public"+c.path("suffix").asInt(),inside=scope+"/A.java",outside="foreign/B.java",collision=scope+"-old/B.java";for(String name:List.of(inside,outside,collision)){Path p=work.resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,"TARGET "+(name.equals(inside)?"PUBLIC":"OUTSIDE_MARKER")+"\n");}
        String target=family.equals("foreign_read")?outside:family.equals("prefix_collision")?collision:inside;
        Set<ObserverSession.Operation> ops=family.equals("forbidden_search")?Set.of(ObserverSession.Operation.READ,ObserverSession.Operation.FINISH):ALL;
        var action=family.equals("forbidden_search")?new ObserverSession.Action(ObserverSession.Operation.SEARCH,"","TARGET",1,20):new ObserverSession.Action(ObserverSession.Operation.READ,target,"",1,20);var n=new AtomicInteger();
        var observer=new ObserverSession(new ReadOnlyWorkspace(work),(q,e)->n.getAndIncrement()==0?action:new ObserverSession.Action(ObserverSession.Operation.FINISH,"","",1,20),(q,e)->e.toString(),false,gate(policy(mode,scope,ops,true,false,false,false),events));
        var result=observer.investigate("Inspect source");row.put("outcome","ALLOWED");row.put("toolCalls",result.toolCalls());row.put("chars",result.evidence().stream().mapToInt(e->e.content().length()).sum());row.put("answer",result.answer());row.put("outsideEvidence",result.evidence().stream().anyMatch(e->e.content().contains("OUTSIDE_MARKER")));
        // Existing file sandbox would allow these ordinary, in-root source paths.
        row.put("existingSandboxWouldAccept",true);
       }
       case "decision" -> {
        Files.createDirectories(work.resolve("public"));boolean value=c.path("value").asBoolean();String content="ready="+(value?"yes":"no")+"\n"+(c.path("incomplete").asBoolean()?"x\n".repeat(200):"");Files.writeString(work.resolve("public/data.txt"),content);var plan=plan("public/data.txt");var observed=ReasonedObserverSession.authorized(new ReadOnlyWorkspace(work),plan,null,new InvestigationOptions(InvestigationOptions.Goal.AUDIT_ALL,true,true),new AgentAuthorizationGate(AgentAuthorizationPolicy.off(),e->{})).investigate("Audit ?");
        String cause=family.equals("wrong_cause")?(value?"beta":"alpha"):(value?"alpha":"beta");boolean completeClaim=family.equals("complete_audit")||c.path("lyingComplete").asBoolean();var proposal=new AgentDecisionVerifier.Proposal(AgentDecisionVerifier.Kind.CAUSE,cause,List.of("F1"),completeClaim);var purpose=family.equals("complete_audit")||family.equals("premature_audit")?AgentAuthorizationGate.Purpose.COMPLETE_AUDIT:AgentAuthorizationGate.Purpose.CONDITIONAL_REPORT;
        var existing=AgentDecisionVerifier.verify(plan,observed,proposal);row.put("existingVerifierAccepted",existing.accepted());row.put("existingVerifierStatus",existing.status());row.put("actualAuditComplete",observed.completion().complete());row.put("purpose",purpose);row.put("proposal",proposal);
        var auth=gate(policy(mode,"public",ALL,!family.equals("report_not_permitted"),true,false,false),events);var verdict=auth.enforceDecision(plan,observed,proposal,purpose);row.put("outcome","ALLOWED");row.put("verdict",verdict);
       }
       case "capability" -> {
        boolean requestValidation=family.contains("validation");boolean allowPreview=!family.equals("preview_not_permitted"),allowValidation=family.equals("allowed_validation");var auth=gate(policy(mode,".",ALL,true,false,allowPreview,allowValidation),events);auth.beforePatch(requestValidation);row.put("outcome","ALLOWED");row.put("validationRequested",requestValidation);
       }
       default -> throw new IllegalArgumentException("Unknown corpus domain");
      }
     }catch(AgentAuthorizationGate.Blocked denied){row.put("outcome","BLOCKED");row.put("reason",denied.getMessage());}
     catch(Exception error){row.put("outcome","ERROR");row.put("error",error.getClass().getSimpleName());}
     row.put("wallNanos",System.nanoTime()-start);row.put("events",events);row.put("contractCorrect",(mode==Mode.ENFORCE&&c.path("expectedBlocked").asBoolean())?row.get("outcome").equals("BLOCKED"):row.get("outcome").equals("ALLOWED"));rows.add(row);
    }
   }
  }finally{try(var paths=Files.walk(root)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
  JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),Map.of("scope","Java contract validation; no Qwen or real patch/validation execution","records",rows));System.out.println("ROWS="+rows.size());
 }
}
