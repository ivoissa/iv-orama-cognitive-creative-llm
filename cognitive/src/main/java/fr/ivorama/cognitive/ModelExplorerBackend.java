package fr.ivorama.cognitive;

import java.util.Collection;

/** Reference adapter; preserves every existing budget and complete-world contract. */
public final class ModelExplorerBackend implements ReasoningBackend {
    public String id() { return "model-explorer"; }
    public ModelExplorer.Analysis analyze(Collection<Formula> premises, Formula question, ModelExplorer.Budget budget) {
        return ModelExplorer.analyze(premises, question, budget);
    }
}
