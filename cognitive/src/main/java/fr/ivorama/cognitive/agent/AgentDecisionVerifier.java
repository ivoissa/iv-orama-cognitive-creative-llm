package fr.ivorama.cognitive.agent;

import fr.ivorama.cognitive.HypothesisReasoningEngine;
import java.util.*;

/**
 * Checks a typed agent claim against *replayed* exact witnesses and the caller's
 * finite, conditional model. This is NOT verification of free-form text,
 * real-world causal truth, nor authorization to execute a proposed action.
 */
public final class AgentDecisionVerifier {
    private AgentDecisionVerifier() { }
    public enum Kind { CAUSE, INDETERMINATE, INCONSISTENT }
    public enum Status { SUPPORTED_CONDITIONALLY, ABSTENTION_COMPATIBLE, REJECTED, INSUFFICIENT_EVIDENCE, INTEGRITY_ERROR }
    public enum Error {
        UNKNOWN_CAUSE, INCORRECT_CAUSE, PREMATURE_CONCLUSION, CONTRADICTORY_EVIDENCE,
        UNSUPPORTED_CITATION, DUPLICATE_CITATION, NO_SUPPORTING_WITNESS,
        UNJUSTIFIED_ABSTENTION, UNJUSTIFIED_CONTRADICTION,
        MISSING_SUPPORT_FOR_CLAIM, AUDIT_NOT_COMPLETE, OBSERVATION_MISMATCH, UNSUPPORTED_STATE
    }
    /** evidenceIds are *claimed supporting sources*, not merely sources mentioned in prose. */
    public record Proposal(Kind kind, String cause, List<String> evidenceIds, boolean claimsCompleteAudit) {
        public Proposal {
            Objects.requireNonNull(kind);
            cause = Objects.requireNonNull(cause);
            evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds));
            if (cause.length() > 80 || evidenceIds.size() > 5)
                throw new IllegalArgumentException("Proposition hors budget");
            if (kind != Kind.CAUSE && !cause.isBlank())
                throw new IllegalArgumentException("Cause interdite pour ce type de conclusion");
            if (kind == Kind.CAUSE && cause.isBlank())
                throw new IllegalArgumentException("Cause requise");
        }
    }
    public record Verdict(Status status, List<Error> errors, String checkedState, String checkedCause,
                          List<String> verifiedEvidenceIds) {
        public Verdict { errors=List.copyOf(errors); verifiedEvidenceIds=List.copyOf(verifiedEvidenceIds); }
        public boolean accepted() { return status==Status.SUPPORTED_CONDITIONALLY || status==Status.ABSTENTION_COMPATIBLE; }
    }

    public static Verdict verify(ReasonedObserverSession.Plan plan, ReasonedObserverSession.Result result,
                                 Proposal proposal) {
        Objects.requireNonNull(plan);Objects.requireNonNull(result);Objects.requireNonNull(proposal);
        var errors=new LinkedHashSet<Error>();
        Map<String,ObserverSession.Evidence> evidence=new LinkedHashMap<>();
        for (var item:result.report().evidence()) {
            if (evidence.putIfAbsent(item.id(),item)!=null) errors.add(Error.OBSERVATION_MISMATCH);
        }
        if (result.report().toolCalls()!=evidence.size() || evidence.size()>5)
            errors.add(Error.OBSERVATION_MISMATCH);
        var selected=new LinkedHashSet<String>();
        for(String id:proposal.evidenceIds()) {
            if (!selected.add(id))errors.add(Error.DUPLICATE_CITATION);
            if (!evidence.containsKey(id))errors.add(Error.UNSUPPORTED_CITATION);
        }
        var allFacts=new LinkedHashMap<String,ReasonedObserverSession.Witness>();
        var selectedFacts=new LinkedHashMap<String,ReasonedObserverSession.Witness>();
        var witnessedIds=new LinkedHashSet<String>();
        Map<String,String> readSignal=new LinkedHashMap<>();
        for(var step:result.steps()) {
            if(readSignal.putIfAbsent(step.evidenceId(),step.signal())!=null)
                errors.add(Error.OBSERVATION_MISMATCH);
            if(!evidence.containsKey(step.evidenceId()))errors.add(Error.OBSERVATION_MISMATCH);
        }
        if(readSignal.size()!=evidence.size())errors.add(Error.OBSERVATION_MISMATCH);
        for(var item:evidence.values()) {
            for(var w:ReasonedObserverSession.recognizedWitnesses(plan,item)) {
                if(!w.signal().equals(readSignal.get(item.id())))continue;
                String key=w.signal()+":"+w.value();
                allFacts.putIfAbsent(key,w);
                if(selected.contains(item.id())) {
                    selectedFacts.putIfAbsent(key,w);
                    witnessedIds.add(item.id());
                }
            }
        }
        if (!proposal.evidenceIds().isEmpty() && !witnessedIds.containsAll(selected))
            errors.add(Error.NO_SUPPORTING_WITNESS);
        var full=replay(plan,allFacts.values());
        var claimed=replay(plan,selectedFacts.values());
        var observed=result.finding();
        if(full.state()!=observed.state() || !Objects.equals(full.cause(),observed.cause()) ||
                !sameFacts(result.facts(),allFacts.values())) errors.add(Error.OBSERVATION_MISMATCH);
        if(proposal.claimsCompleteAudit() && (result.completion().goal()==InvestigationOptions.Goal.DIAGNOSE ||
                !result.completion().complete() || result.report().evidenceLimitReached() ||
                !result.completion().pendingSignals().isEmpty() ||
                !result.completion().unavailableSignals().isEmpty() ||
                !result.completion().unreadSignals().isEmpty() ||
                !completeReadCoverage(plan,evidence.values(),readSignal))) errors.add(Error.AUDIT_NOT_COMPLETE);
        if(proposal.kind()==Kind.CAUSE) {
            if(!plan.hypotheses().containsKey(proposal.cause())) errors.add(Error.UNKNOWN_CAUSE);
            if(full.state()==HypothesisReasoningEngine.State.INCONSISTENT)
                errors.add(Error.CONTRADICTORY_EVIDENCE);
            else if(full.state()!=HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY)
                errors.add(Error.PREMATURE_CONCLUSION);
            else if(!full.cause().equals(proposal.cause()))errors.add(Error.INCORRECT_CAUSE);
            if(selectedFacts.isEmpty() || claimed.state()!=HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY ||
                    !claimed.cause().equals(proposal.cause())) errors.add(Error.MISSING_SUPPORT_FOR_CLAIM);
        } else if(proposal.kind()==Kind.INCONSISTENT) {
            if(full.state()!=HypothesisReasoningEngine.State.INCONSISTENT)
                errors.add(Error.UNJUSTIFIED_CONTRADICTION);
            if(selectedFacts.isEmpty() || claimed.state()!=HypothesisReasoningEngine.State.INCONSISTENT)
                errors.add(Error.MISSING_SUPPORT_FOR_CLAIM);
        } else {
            if(full.state()==HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY ||
                    full.state()==HypothesisReasoningEngine.State.INCONSISTENT)
                errors.add(Error.UNJUSTIFIED_ABSTENTION);
            else if(full.state()!=HypothesisReasoningEngine.State.UNDETERMINED)
                errors.add(Error.UNSUPPORTED_STATE);
            // Abstention is safe; its asserted *correctness* is not an empirical certificate.
        }
        Status status;
        if(errors.contains(Error.OBSERVATION_MISMATCH)) status=Status.INTEGRITY_ERROR;
        else if(errors.isEmpty()) status=proposal.kind()==Kind.INDETERMINATE
                ?Status.ABSTENTION_COMPATIBLE:Status.SUPPORTED_CONDITIONALLY;
        else if(errors.stream().allMatch(e->e==Error.PREMATURE_CONCLUSION || e==Error.MISSING_SUPPORT_FOR_CLAIM || e==Error.NO_SUPPORTING_WITNESS))
            status=Status.INSUFFICIENT_EVIDENCE;
        else status=Status.REJECTED;
        return new Verdict(status,List.copyOf(errors),full.state().name(),full.cause(),List.copyOf(witnessedIds));
    }

    /** Completion metadata alone cannot prove that every declared probe was read to its end. */
    private static boolean completeReadCoverage(ReasonedObserverSession.Plan plan,
            Collection<ObserverSession.Evidence> evidence,Map<String,String> readSignal) {
        var last=new HashMap<String,ObserverSession.Evidence>();
        for(var item:evidence) {
            String signal=readSignal.get(item.id());
            if(signal==null || !item.operation().equals("READ"))return false;
            last.put(signal,item);
        }
        for(var probe:plan.probes()) {
            var item=last.get(probe.signal());
            if(item==null || item.truncated())return false;
            String expected=java.nio.file.Path.of(probe.path().replace('\\','/')).normalize().toString();
            String actual=java.nio.file.Path.of(item.request().replace('\\','/')).normalize().toString();
            if(!expected.equals(actual))return false;
        }
        return true;
    }

    /**
     * Independently checks the structured decision ACTUALLY returned by the observer,
     * without asking a second language model to invent a parallel decision.
     * This is a conditional consistency/integrity audit, NOT semantic validation
     * of the caller's model, the raw files, or unconstrained Qwen prose.
     */
    public static Verdict verifyObservedDecision(ReasonedObserverSession.Plan plan,
                                                  ReasonedObserverSession.Result result) {
        Objects.requireNonNull(plan);Objects.requireNonNull(result);
        Kind kind=switch(result.finding().state()) {
            case PROVEN_CONDITIONALLY -> Kind.CAUSE;
            case INCONSISTENT -> Kind.INCONSISTENT;
            default -> Kind.INDETERMINATE;
        };
        String cause=kind==Kind.CAUSE ? Objects.requireNonNull(result.finding().cause()) : "";
        boolean completedAudit=result.completion().goal()!=InvestigationOptions.Goal.DIAGNOSE
                && result.completion().complete();
        var ids=result.report().evidence().stream().map(ObserverSession.Evidence::id).toList();
        if(kind==Kind.INDETERMINATE) return verify(plan,result,
                new Proposal(kind,"",List.of(),completedAudit));
        // At most five observed reads -> at most 31 nonempty candidate subsets.
        // A minimal evidence subset makes supporting citations explicit, rather
        // than blindly treating every visited file as a justification.
        Verdict fallback=null;
        int total=ids.size();
        if(total>5) return verify(plan,result,new Proposal(kind,cause,List.of(),completedAudit));
        for(int count=1;count<=total;count++) {
            for(int mask=1;mask<(1<<total);mask++) {
                if(Integer.bitCount(mask)!=count)continue;
                var selected=new ArrayList<String>();
                for(int i=0;i<total;i++)if((mask&(1<<i))!=0)selected.add(ids.get(i));
                Verdict verdict=verify(plan,result,new Proposal(kind,cause,selected,completedAudit));
                if(verdict.status()==Status.INTEGRITY_ERROR)return verdict;
                if(verdict.accepted())return verdict;
                fallback=verdict;
            }
        }
        return fallback==null ? verify(plan,result,new Proposal(kind,cause,List.of(),completedAudit)) : fallback;
    }

    public enum AuditMode { OFF, DIRECT, QWEN }
    /** Old verifyDecisions=true still means QWEN unless an explicit new mode is set. */
    public static AuditMode auditModeFromProperties() {
        String value=System.getProperty("ivorama.agent.decisionAuditMode","").trim();
        if(!value.isEmpty())return AuditMode.valueOf(value.toUpperCase(Locale.ROOT));
        String legacy=System.getProperty("ivorama.agent.verifyDecisions","false");
        if(!legacy.equalsIgnoreCase("true")&&!legacy.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("Booléen attendu : ivorama.agent.verifyDecisions");
        return Boolean.parseBoolean(legacy)?AuditMode.QWEN:AuditMode.OFF;
    }

    private static HypothesisReasoningEngine.Finding replay(ReasonedObserverSession.Plan plan,
                                     Collection<ReasonedObserverSession.Witness> facts) {
        var engine=new HypothesisReasoningEngine(plan.name(),plan.exhaustiveUnique());
        for(var name:plan.hypotheses().keySet())engine.candidate(name);
        plan.hypotheses().forEach((name,predictions)->predictions.forEach((signal,value)->engine.predicts(name,signal,value)));
        for(var w:facts)engine.observe(w.signal(),w.value(),w.source());
        return engine.evaluate();
    }
    private static boolean sameFacts(List<HypothesisReasoningEngine.Observation> reported,
                                     Collection<ReasonedObserverSession.Witness> observed) {
        var actual=new LinkedHashSet<String>();
        for(var fact:reported)actual.add(fact.signal()+":"+fact.value()+":"+fact.source());
        var expected=new LinkedHashSet<String>();
        for(var w:observed)expected.add(w.signal()+":"+w.value()+":"+w.source());
        return actual.equals(expected) && reported.size()==expected.size();
    }
}
