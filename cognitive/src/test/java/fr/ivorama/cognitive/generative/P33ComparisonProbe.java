package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.UserMessage;
import fr.ivorama.cognitive.*;
import java.nio.file.*;
import java.util.*;

/** Independent, hand-labelled fixture oracle, no claims about real model accuracy. */
public final class P33ComparisonProbe {
    private record Case(String id,String model,String reference,String draft,
                        P31CandidateAnswerAudit.Status expected,P32SuggestedCorrections.Action action) {}
    public static void main(String[] args) throws Exception {
        List<String> input=Files.readAllLines(Path.of("evaluation/p33/provider-independent-oracles.tsv"));
        List<Case> cases=new ArrayList<>();
        for (String row:input.subList(1,input.size())) {
            String[] p=row.split("\t",-1);
            if(p.length!=6)throw new AssertionError("Bad oracle row: "+row);
            cases.add(new Case(p[0],p[1],p[2],p[3],P31CandidateAnswerAudit.Status.valueOf(p[4]),
                    P32SuggestedCorrections.Action.valueOf(p[5])));
        }
        int verified=0, detected=0, unsupported=0, refused=0, corrections=0;
        int assertions=0;
        for (Case c:cases) for (String model:List.of(c.model,"other-backend","enterprise-model")) {
            var observed=new ArrayList<ObservedGenerationBackend.Observation>();
            GenerationBackend scripted=(history,stream)->new GenerationBackend.Result(c.draft,model,"STOP",1,1,1,1);
            GenerationBackend decorated=new ObservedGenerationBackend(scripted,c.reference,
                    ModelExplorer.Budget.small(),observed::add);
            var original=decorated.generate(List.of(UserMessage.from("question")),chunk->{});
            if(!original.text().equals(c.draft))throw new AssertionError(c.id+": output changed");assertions++;
            if(observed.size()!=1)throw new AssertionError(c.id+": event count");assertions++;
            var review=observed.getFirst().review().orElseThrow();
            if(review.status()!=c.expected)throw new AssertionError(c.id+": "+review.status()+" vs "+c.expected);assertions++;
            var advice=P32SuggestedCorrections.propose(review,ModelExplorer.Budget.small());
            P32SuggestedCorrections.Action actual=advice.suggestions().isEmpty()
                    ? P32SuggestedCorrections.Action.NONE : advice.suggestions().getFirst().action();
            if(actual!=c.action)throw new AssertionError(c.id+": "+actual+" vs "+c.action);assertions++;
            if(advice.permitsAutomaticReplacement())throw new AssertionError(c.id+": rewriting enabled");assertions++;
            if(c.action==P32SuggestedCorrections.Action.CHALLENGE) {
                if(advice.suggestions().getFirst().referenceExcerpts().isEmpty())
                    throw new AssertionError(c.id+": no declared evidence");
                corrections++;
            }
            if(c.expected==P31CandidateAnswerAudit.Status.CONTRADICTED)detected++;
            if(c.expected==P31CandidateAnswerAudit.Status.CONSISTENT_UNSUPPORTED)unsupported++;
            if(c.expected==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE
                || c.expected==P31CandidateAnswerAudit.Status.INCONSISTENT_REFERENCE)refused++;
            verified++;
        }
        System.out.printf(Locale.ROOT,"P3.3,SIMULATED,fixtures=%d,provider_runs=%d,assertions=%d,contradictions_flagged=%d,needs_source=%d,refusals=%d,sourced_challenges=%d,wrong_flags=0,outputs_modified=0%n",
            cases.size(),verified,assertions,detected,unsupported,refused,corrections);
    }
}
