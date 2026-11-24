package fr.ivorama.cognitive;
import java.util.*;
public final class P32SuggestionsProbe {
  private static int tests;
  private static void ok(boolean x,String message){tests++;if(!x)throw new AssertionError(message);}
  private static P32SuggestedCorrections.Advice advice(String facts,String draft){
    var budget=ModelExplorer.Budget.small();
    return P32SuggestedCorrections.propose(P31CandidateAnswerAudit.inspect("fixture-provider",facts,draft,budget),budget);
  }
  public static void main(String[] args) {
    var ref="La porte A est ouverte. Le document B est valide.";
    var no=advice(ref,"La porte A n'est pas ouverte.");
    ok(no.review().status()==P31CandidateAnswerAudit.Status.CONTRADICTED,"contradiction recognized");
    ok(no.suggestions().size()==1,"one suggestion");
    var s=no.suggestions().getFirst();
    ok(s.action()==P32SuggestedCorrections.Action.CHALLENGE,"sourced challenge");
    ok(s.referenceExcerpts().equals(List.of("La porte A est ouverte")),"only needed reference excerpt");
    ok(!no.permitsAutomaticReplacement(),"never rewrite");
    ok(no.complete(),"complete minimal support");
    var unsure=advice(ref,"La porte A est fermée.");
    ok(unsure.suggestions().getFirst().action()==P32SuggestedCorrections.Action.NEEDS_SOURCE,"unsupported is not contradicted");
    ok(unsure.suggestions().getFirst().referenceExcerpts().isEmpty(),"unsupported no evidence fabricated");
    var right=advice(ref,"La porte A est ouverte.");
    ok(right.suggestions().isEmpty(),"established claim no correction");
    var internal=advice(ref,"La porte C est ouverte. La porte C n'est pas ouverte.");
    ok(internal.review().status()==P31CandidateAnswerAudit.Status.CONTRADICTED,"draft internal conflict");
    ok(internal.suggestions().stream().anyMatch(x->x.action()==P32SuggestedCorrections.Action.INTERNAL_CONFLICT),"internal conflict surfaced");
    var unknown=advice(ref,"« La porte A n'est pas ouverte ». ");
    ok(unknown.suggestions().getFirst().action()==P32SuggestedCorrections.Action.UNRESOLVED,"quote not treated as fact");
    var inconsistent=advice("La porte A est ouverte. La porte A n'est pas ouverte.","La porte A est ouverte.");
    ok(inconsistent.suggestions().getFirst().action()==P32SuggestedCorrections.Action.UNRESOLVED,"inconsistent baseline no suggested correction");
    var duplicates=advice("La porte A est ouverte. La porte A est ouverte. Le document B est valide.","La porte A n'est pas ouverte.");
    ok(duplicates.suggestions().getFirst().referenceExcerpts().size()==1,"source alternatives not cumulative proof requirements");
    var restricted=ModelExplorer.Budget.small();
    var altered=P31CandidateAnswerAudit.inspect("fake",ref,"La porte A n'est pas ouverte.",restricted);
    var result=P32SuggestedCorrections.propose(altered,new ModelExplorer.Budget(0,1));
    ok(!result.complete() && result.suggestions().getFirst().action()==P32SuggestedCorrections.Action.UNRESOLVED,
        "no false support under restricted budget");
    for (String vendor:List.of("Qwen","GPT","Claude","Gemini","custom-model")){
      var item=P32SuggestedCorrections.propose(P31CandidateAnswerAudit.inspect(vendor,ref,"La porte A n'est pas ouverte.",restricted),restricted);
      ok(item.suggestions().getFirst().equals(s),"vendor-independent suggestion "+vendor);
    }
    var fakeReview=new P31CandidateAnswerAudit.Review("fake",ref,"La porte A est ouverte.",
        P31CandidateAnswerAudit.Status.CONTRADICTED,
        List.of(new P31CandidateAnswerAudit.Claim("La porte A est ouverte",P31CandidateAnswerAudit.Status.CONTRADICTED,"txt_p1")),
        List.of(),"fabricated evidence");
    var safe=P32SuggestedCorrections.propose(fakeReview,ModelExplorer.Budget.small());
    ok(!safe.complete(),"forged contradiction cannot be sourced");
    ok(safe.suggestions().stream().noneMatch(n->n.action()==P32SuggestedCorrections.Action.CHALLENGE),
        "no fabricated sourced challenge");
    System.out.println("P32SuggestionsProbe PASS "+tests+" checks");
  }
}
