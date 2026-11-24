package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;
import java.util.List;
import java.util.Optional;
/** Sélection explicable de synset; l'absence de preuve donne Optional.empty(). */
@FunctionalInterface public interface SemanticSimilarityScorer {
    Optional<String> selectSense(TokenData target, SentenceAnalysis context, List<String> synsets);
}
