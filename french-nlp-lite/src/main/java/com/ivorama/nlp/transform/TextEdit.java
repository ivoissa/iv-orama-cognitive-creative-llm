package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.LexiconMorphologicalEngine;
import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;
import java.util.Objects;
import java.util.Optional;

/** Édition sur les offsets UTF-16 relatifs à la phrase originale. */
public record TextEdit(int beginOffset, int endOffset, String replacement, int priority,
                       String ruleName, Optional<TokenData> sourceTokenForCasing) {
    public TextEdit(int beginOffset, int endOffset, String replacement, int priority) {
        this(beginOffset, endOffset, replacement, priority, "", Optional.empty());
    }

    public TextEdit {
        if (beginOffset < 0 || endOffset < beginOffset) {
            throw new IllegalArgumentException("Offsets d’édition invalides");
        }
        Objects.requireNonNull(replacement, "replacement");
        Objects.requireNonNull(ruleName, "ruleName");
        Objects.requireNonNull(sourceTokenForCasing, "sourceTokenForCasing");
    }

    static TextEdit replace(SentenceAnalysis sentence, TokenData token, String value, int priority, String ruleName) {
        if (!hasSurface(sentence, token)) throw new IllegalArgumentException("Surface du token incompatible avec ses offsets");
        return new TextEdit(token.beginOffset(), token.endOffset(),
                value, priority, ruleName, Optional.of(token));
    }

    static boolean hasSurface(SentenceAnalysis sentence, TokenData token) {
        return token.beginOffset() >= 0 && token.endOffset() <= sentence.originalText().length()
                && sentence.originalText().substring(token.beginOffset(), token.endOffset()).equals(token.word());
    }

    String renderedReplacement() {
        return sourceTokenForCasing.map(token -> LexiconMorphologicalEngine.caseLike(token.word(), replacement))
                .orElse(replacement);
    }

    boolean overlaps(TextEdit other) {
        if (beginOffset == endOffset && other.beginOffset == other.endOffset) {
            return beginOffset == other.beginOffset;
        }
        return beginOffset < other.endOffset && other.beginOffset < endOffset;
    }
}
