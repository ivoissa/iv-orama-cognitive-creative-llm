package fr.ivorama.cognitive;

import java.util.Collection;

/** Semantic diagnosis only: never a ProofChecker certificate or an RDO world enumeration. */
public interface ReasoningBackend {
    String id();
    ModelExplorer.Analysis analyze(Collection<Formula> premises, Formula question, ModelExplorer.Budget budget);
}
