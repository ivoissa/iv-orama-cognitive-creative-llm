package fr.ivorama.cognitive;
import java.util.Collection;
/** Trusted application extension; never a source of ProofChecker certificates. */
public interface SemanticExplanationProvider {
    SemanticExplanation explain(Collection<SemanticExplanation.Premise> premises,Formula question);
    boolean matches(SemanticExplanation explanation,Collection<SemanticExplanation.Premise> premises,Formula question);
}
