package fr.ivorama.cognitive;
import java.util.*;
/** Explicit P3.4 semantic coverage oracle: partial evidence is not whole-text certification. */
public final class P34PartialSemanticProbe {
  private static int count;
  private static void ok(boolean condition,String message){count++;if(!condition)throw new AssertionError(message);}
  private static P31CandidateAnswerAudit.Review inspect(String reference,String draft){
    return P31CandidateAnswerAudit.inspect("vendor-agnostic",reference,draft,ModelExplorer.Budget.small());
  }
  public static void main(String[] args){
    String reference="La porte A est ouverte. Le document B est valide.";
    var mixed=inspect(reference,"La porte A n'est pas ouverte. Cela semble évident.");
    ok(mixed.status()==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,"partial whole-answer unknown");
    ok(!mixed.isFullySupported(),"never certify incomplete coverage");
    ok(!mixed.unresolved().isEmpty(),"precise unresolved diagnostics");
    ok(mixed.claims().size()==1,"preserve interpreted claim diagnostic");
    ok(mixed.claims().getFirst().status()==P31CandidateAnswerAudit.Status.CONTRADICTED,"independent partial contradiction");
    var notes=P32SuggestedCorrections.propose(mixed,ModelExplorer.Budget.small());
    ok(!notes.complete() && !notes.permitsAutomaticReplacement(),"partial never automatically repaired");
    ok(notes.suggestions().getFirst().action()==P32SuggestedCorrections.Action.UNRESOLVED,"limitation before suggestions");
    ok(notes.suggestions().stream().anyMatch(s->s.action()==P32SuggestedCorrections.Action.CHALLENGE
            && s.referenceExcerpts().equals(List.of("La porte A est ouverte"))),"sourced partial contradiction");
    var partialGood=inspect(reference,"Le document B est valide. Cependant, rien n'est sûr.");
    ok(partialGood.status()==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,"partial supported not whole supported");
    ok(!partialGood.claims().isEmpty() && partialGood.claims().getFirst().status()==P31CandidateAnswerAudit.Status.ENTAILED,
            "partial claim can be independently established");
    var unsafeReference=inspect("La porte A est ouverte. Cela reste à confirmer.","La porte A est ouverte.");
    ok(unsafeReference.status()==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,"ambiguous reference rejected");
    ok(unsafeReference.claims().isEmpty(),"no claims inferred from partially translated reference");
    var quote=inspect(reference,"« La porte A n'est pas ouverte ». Le document B est valide.");
    ok(quote.status()==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,"quote is not fact");
    ok(!quote.isFullySupported(),"quoted fragment prevents global certification");
    var pronoun=inspect(reference,"Elle est ouverte. Le document B est valide.");
    ok(pronoun.status()==P31CandidateAnswerAudit.Status.UNRESOLVED_LANGUAGE,"pronoun unresolved");
    var subj=inspect(reference,"La porte A est ouverte. La porte A n'est pas ouverte.");
    ok(subj.status()==P31CandidateAnswerAudit.Status.CONTRADICTED,"fully translated conflict still detected");
    ok(subj.claims().size()==2,"claim count unchanged");
    var noComplete=inspect(reference,"C'est probablement exact.");
    ok(noComplete.claims().isEmpty(),"no invented evidence for wholly uninterpreted draft");
    var lowBudget=P31CandidateAnswerAudit.inspect("fake",reference,"La porte A est ouverte. Cela semble évident.",
            new ModelExplorer.Budget(0,1));
    ok(lowBudget.status()==P31CandidateAnswerAudit.Status.RESOURCE_LIMIT,"partial under resource limit cannot be certified");
    System.out.println("P34PartialSemanticProbe PASS "+count+" assertions");
  }
}
