package com.ivorama.nlp.core;
import java.util.Optional;
/** Contrat morphologique. Ne promet pas une flexion sans ressource linguistique. */
public interface MorphologicalEngine {
    MorphologicalFeatures analyze(TokenData token);
    Optional<String> inflect(String lemma, MorphologicalFeatures target);
}
