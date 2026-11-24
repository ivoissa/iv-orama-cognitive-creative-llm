package fr.ivorama.cognitive;

import java.util.*;

/** Optional hint -> actual proof construction. No SAT result or provider flag is a certificate. */
public final class GuidedProofSearch {
    private GuidedProofSearch() {}
    public enum GuidanceStatus { USED, UNUSED, SKIPPED, STALE, INVALID, FAILED }
    public record Result(ProofSearch.SearchOutcome outcome, GuidanceStatus guidance,
                         List<String> selectedSupportIds, int examinedSupports, boolean fallbackUsed,
                         List<SemanticExplanation.Premise> contextSnapshot, Formula goal) {
        public Result {
            Objects.requireNonNull(outcome);Objects.requireNonNull(guidance);Objects.requireNonNull(goal);
            selectedSupportIds=List.copyOf(selectedSupportIds);contextSnapshot=List.copyOf(contextSnapshot);
        }
        /** Conservative provenance freshness: includes ids, formula, source, excerpt, order and goal. */
        public boolean isCurrent(Collection<SemanticExplanation.Premise> current,Formula currentGoal) {
            return goal.equals(currentGoal) && contextSnapshot.equals(List.copyOf(current));
        }
    }
    public static Result find(SemanticExplanationProvider provider,
                              Collection<SemanticExplanation.Premise> declared, Formula goal,
                              int maxDerived,long maxSearchOperations,int maxSupportAttempts) {
        Objects.requireNonNull(provider);Objects.requireNonNull(goal);
        if(maxDerived<1 || maxSearchOperations<1 || maxSupportAttempts<0 || maxSupportAttempts>32)
            throw new IllegalArgumentException("Positive search budgets and 0..32 support attempts required");
        List<SemanticExplanation.Premise> snapshot=List.copyOf(declared);
        Map<String,SemanticExplanation.Premise> byId=new LinkedHashMap<>();
        for(var p:snapshot)if(byId.putIfAbsent(p.id(),p)!=null)throw new IllegalArgumentException("Duplicate premise id: "+p.id());
        List<Formula> full=snapshot.stream().map(SemanticExplanation.Premise::formula).toList();
        GuidanceStatus status=maxSupportAttempts==0?GuidanceStatus.SKIPPED:GuidanceStatus.UNUSED;
        long used=0;int examined=0;
        SemanticExplanation explanation=null;
        if(maxSupportAttempts>0)try {
            explanation=provider.explain(snapshot,goal);
            if(explanation==null)status=GuidanceStatus.FAILED;
            else if(!provider.matches(explanation,snapshot,goal))status=GuidanceStatus.STALE;
            else if(explanation.kind()!=SemanticExplanation.Kind.IMPLICATION ||
                    explanation.semanticStatus()!=ModelExplorer.Status.PROVEN_TRUE ||
                    !explanation.target().equals(Optional.of(goal)) ||
                    explanation.status()==SemanticExplanation.Status.FAILED ||
                    explanation.status()==SemanticExplanation.Status.NOT_APPLICABLE)
                status=GuidanceStatus.UNUSED;
            else for(var core:explanation.supports()) {
                if(examined==maxSupportAttempts || used==maxSearchOperations)break;
                examined++;
                Set<String> ids=new LinkedHashSet<>();boolean valid=true;
                for(var p:core.premises()) {
                    // Formula/source/excerpt/ID must all be from the actual declared context.
                    if(!p.equals(byId.get(p.id())) || !ids.add(p.id())){valid=false;break;}
                }
                if(!valid){status=GuidanceStatus.INVALID;continue;}
                var subset=core.premises().stream().map(SemanticExplanation.Premise::formula).toList();
                var attempt=ProofSearch.findIndexedWithBudget(subset,goal,maxDerived,maxSearchOperations-used);
                used+=attempt.operations();
                if(attempt.proof().isPresent()) {
                    // A trusted-looking semantic hint is still not accepted without this full-context check.
                    var checked=ProofChecker.verify(attempt.proof().orElseThrow(),full);
                    if(checked.valid() && goal.equals(checked.conclusion())) {
                        var outcome=new ProofSearch.SearchOutcome(ProofSearch.SearchStatus.FOUND,attempt.proof(),used);
                        return new Result(outcome,GuidanceStatus.USED,List.copyOf(ids),examined,false,snapshot,goal);
                    }
                    status=GuidanceStatus.INVALID;
                }
            }
        } catch(RuntimeException failure) {
            status=GuidanceStatus.FAILED;
        }
        if(used==maxSearchOperations)
            return new Result(new ProofSearch.SearchOutcome(ProofSearch.SearchStatus.RESOURCE_LIMIT,
                    Optional.empty(),used),status,List.of(),examined,false,snapshot,goal);
        var fallback=ProofSearch.findIndexedWithBudget(full,goal,maxDerived,maxSearchOperations-used);
        var outcome=new ProofSearch.SearchOutcome(fallback.status(),fallback.proof(),used+fallback.operations());
        return new Result(outcome,status,List.of(),examined,true,snapshot,goal);
    }
}
