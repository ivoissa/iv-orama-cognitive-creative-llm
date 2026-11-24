package fr.ivorama.cognitive.security;

import fr.ivorama.cognitive.agent.*;
import fr.ivorama.cognitive.security.LlmSecurityPolicy.Mode;
import java.util.*;import java.util.function.Consumer;

/** Policy authorization is distinct from conditional consistency and does not authorize writes. */
public final class AgentAuthorizationGate {
    public enum Status { BYPASSED, ALLOW, DENY, NEEDS_EVIDENCE }
    public enum Purpose { CONDITIONAL_REPORT, COMPLETE_AUDIT }
    public enum Reason { DISABLED, OPERATION_NOT_ALLOWED, INVALID_PATH, OUTSIDE_SCOPE,
        READ_REQUIRED, PATCH_NOT_ALLOWED, VALIDATION_NOT_ALLOWED, REPORT_NOT_ALLOWED, AUDIT_NOT_COMPLETE, DECISION_REJECTED, INTEGRITY_ERROR, CONTROL_FAILURE }
    public record Verdict(Mode mode,Status status,List<Reason> reasons,String conditionalStatus) {
        public Verdict { reasons=List.copyOf(reasons); }
    }
    public record Event(Mode mode,String domain,Status status,List<Reason> reasons,boolean scopedSearch) {
        public Event{reasons=List.copyOf(reasons);}
    }
    public static final class Blocked extends SecurityException {
        public Blocked(Verdict verdict){super("AGENT_AUTHORIZATION: "+verdict.status()+" "+verdict.reasons());}
    }
    private final AgentAuthorizationPolicy policy;private final Consumer<Event> events;
    public AgentAuthorizationGate(AgentAuthorizationPolicy policy,Consumer<Event> events){this.policy=Objects.requireNonNull(policy);this.events=Objects.requireNonNull(events);}
    public static AgentAuthorizationGate configured(){return new AgentAuthorizationGate(AgentAuthorizationPolicy.fromSystemProperties(),e->System.getLogger(AgentAuthorizationGate.class.getName()).log(System.Logger.Level.WARNING,e.toString()));}
    public AgentAuthorizationPolicy policy(){return policy;}
    private Verdict verdict(Status status,Reason reason){return new Verdict(policy.mode(),status,List.of(reason),"");}
    public Verdict evaluateAction(ObserverSession.Action action,List<ObserverSession.Evidence> evidence){
        if(policy.mode()==Mode.OFF)return verdict(Status.BYPASSED,Reason.DISABLED);
        if(!policy.operations().contains(action.operation()))return verdict(Status.DENY,Reason.OPERATION_NOT_ALLOWED);
        if(action.operation()==ObserverSession.Operation.LIST||action.operation()==ObserverSession.Operation.READ){
            try{if(!policy.permitsPath(action.path()))return verdict(Status.DENY,Reason.OUTSIDE_SCOPE);}
            catch(IllegalArgumentException invalid){return verdict(Status.DENY,Reason.INVALID_PATH);}
        }
        if(action.operation()==ObserverSession.Operation.FINISH&&policy.requireReadBeforeFinish()&&evidence.stream().noneMatch(e->e.operation().equals("READ")))
            return verdict(Status.NEEDS_EVIDENCE,Reason.READ_REQUIRED);
        return new Verdict(policy.mode(),Status.ALLOW,List.of(),"");
    }
    public void beforeAction(ObserverSession.Action action,List<ObserverSession.Evidence> evidence){
        if(policy.mode()==Mode.OFF)return;
        var verdict=evaluateAction(action,evidence);boolean scopedSearch=action.operation()==ObserverSession.Operation.SEARCH&&!policy.readScope().equals(".");
        enforce(verdict,"ACTION",scopedSearch);
    }
    /** AUDIT keeps original execution. ENFORCE scopes discovery, including the bootstrap LIST. */
    public String bootstrapPath(){return policy.mode()==Mode.ENFORCE?policy.readScope():".";}
    public String searchPath(){return policy.mode()==Mode.ENFORCE?policy.readScope():".";}
    public void beforeSynthesis(List<ObserverSession.Evidence> evidence){
        if(policy.mode()==Mode.OFF||!policy.requireReadBeforeFinish())return;
        if(evidence.stream().noneMatch(e->e.operation().equals("READ")))enforce(verdict(Status.NEEDS_EVIDENCE,Reason.READ_REQUIRED),"SYNTHESIS",false);
    }
    /** Existing isolated preview/validation may copy the whole project; partial scopes cannot grant them. */
    public void beforePatch(boolean validationRequested){
        if(policy.mode()==Mode.OFF)return;
        if(!policy.allowPatchPreview())enforce(verdict(Status.DENY,Reason.PATCH_NOT_ALLOWED),"PATCH",false);
        if(validationRequested&&!policy.allowIsolatedValidation())enforce(verdict(Status.DENY,Reason.VALIDATION_NOT_ALLOWED),"VALIDATION",false);
    }
    /** The plan and receipt are application-owned. Only the proposal is allowed to originate in a model. */
    public Verdict evaluateDecision(ReasonedObserverSession.Plan plan,ReasonedObserverSession.Result result,
            AgentDecisionVerifier.Proposal proposal,Purpose purpose){
        if(policy.mode()==Mode.OFF)return verdict(Status.BYPASSED,Reason.DISABLED);
        try { return checkedDecision(plan,result,proposal,purpose); }
        catch(RuntimeException invalid) { return verdict(Status.DENY,Reason.INTEGRITY_ERROR); }
    }
    private Verdict checkedDecision(ReasonedObserverSession.Plan plan,ReasonedObserverSession.Result result,
            AgentDecisionVerifier.Proposal proposal,Purpose purpose){
        Objects.requireNonNull(plan);Objects.requireNonNull(result);Objects.requireNonNull(proposal);Objects.requireNonNull(purpose);
        if(purpose==Purpose.COMPLETE_AUDIT?!policy.allowCompleteAudit():!policy.allowConditionalReport())return verdict(Status.DENY,Reason.REPORT_NOT_ALLOWED);
        for(var e:result.report().evidence()){
            try{if(!policy.permitsPath(e.request()))return verdict(Status.DENY,Reason.OUTSIDE_SCOPE);}
            catch(IllegalArgumentException invalid){return verdict(Status.DENY,Reason.INVALID_PATH);}
        }
        var checked=AgentDecisionVerifier.verify(plan,result,proposal);
        if(checked.status()==AgentDecisionVerifier.Status.INTEGRITY_ERROR)return new Verdict(policy.mode(),Status.DENY,List.of(Reason.INTEGRITY_ERROR),checked.status().name());
        if(!checked.accepted())return new Verdict(policy.mode(),checked.status()==AgentDecisionVerifier.Status.INSUFFICIENT_EVIDENCE?Status.NEEDS_EVIDENCE:Status.DENY,List.of(Reason.DECISION_REJECTED),checked.status().name());
        if(purpose==Purpose.COMPLETE_AUDIT&&(!proposal.claimsCompleteAudit()||!result.completion().complete()||result.completion().goal()==InvestigationOptions.Goal.DIAGNOSE))return verdict(Status.NEEDS_EVIDENCE,Reason.AUDIT_NOT_COMPLETE);
        return new Verdict(policy.mode(),Status.ALLOW,List.of(),checked.status().name());
    }
    public Verdict enforceDecision(ReasonedObserverSession.Plan plan,ReasonedObserverSession.Result result,AgentDecisionVerifier.Proposal proposal,Purpose purpose){
        var verdict=evaluateDecision(plan,result,proposal,purpose);enforce(verdict,"DECISION",false);return verdict;
    }
    /** Use the actual observed decision, with supporting READ IDs rather than a new model inference. */
    public void beforeReasonedComment(ReasonedObserverSession.Plan plan,ReasonedObserverSession.Result result){
        if(policy.mode()==Mode.OFF)return;
        var kind=switch(result.finding().state()){
            case PROVEN_CONDITIONALLY -> AgentDecisionVerifier.Kind.CAUSE;
            case INCONSISTENT -> AgentDecisionVerifier.Kind.INCONSISTENT;
            default -> AgentDecisionVerifier.Kind.INDETERMINATE;
        };
        var ids=new LinkedHashSet<String>();
        if(kind!=AgentDecisionVerifier.Kind.INDETERMINATE)for(var step:result.steps())for(var e:result.report().evidence()){
            if(!step.evidenceId().equals(e.id()))continue;
            if(ReasonedObserverSession.recognizedWitnesses(plan,e).stream().anyMatch(w->w.signal().equals(step.signal())))ids.add(e.id());
        }
        boolean completed=result.completion().goal()!=InvestigationOptions.Goal.DIAGNOSE&&result.completion().complete();
        var proposal=new AgentDecisionVerifier.Proposal(kind,kind==AgentDecisionVerifier.Kind.CAUSE?result.finding().cause():"",List.copyOf(ids),completed);
        enforceDecision(plan,result,proposal,policy.requireCompleteAudit()?Purpose.COMPLETE_AUDIT:Purpose.CONDITIONAL_REPORT);
    }
    private void enforce(Verdict verdict,String domain,boolean scopedSearch){
        if(policy.mode()==Mode.OFF)return;
        if(verdict.status()!=Status.ALLOW||scopedSearch)try{events.accept(new Event(policy.mode(),domain,verdict.status(),verdict.reasons(),scopedSearch));}
        catch(RuntimeException failed){if(policy.mode()==Mode.ENFORCE)throw new Blocked(verdict(Status.DENY,Reason.CONTROL_FAILURE));}
        if(policy.mode()==Mode.ENFORCE&&verdict.status()!=Status.ALLOW)throw new Blocked(verdict);
    }
}
