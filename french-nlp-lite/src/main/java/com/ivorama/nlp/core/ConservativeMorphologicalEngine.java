package com.ivorama.nlp.core;
import java.util.Optional;
/** Adaptateur sans lexique de flexion : identité uniquement, jamais de genre inventé. */
public final class ConservativeMorphologicalEngine implements MorphologicalEngine {
    public MorphologicalFeatures analyze(TokenData token) {
        return new MorphologicalFeatures(token.word(), token.pos(), "", "");
    }
    public Optional<String> inflect(String lemma, MorphologicalFeatures target) {
        if (lemma == null || lemma.isBlank() || target == null) return Optional.empty();
        return lemma.equals(target.lemma()) ? Optional.of(lemma) : Optional.empty();
    }
}
