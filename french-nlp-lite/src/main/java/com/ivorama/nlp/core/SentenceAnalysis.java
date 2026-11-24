package com.ivorama.nlp.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SentenceAnalysis(
        String originalText,
        List<TokenData> tokens,
        int rootTokenIndex,
        Constituent subject,
        List<Constituent> objects,
        List<Constituent> circumstantials, int beginOffset, int endOffset) {

    public SentenceAnalysis(String originalText, List<TokenData> tokens, int rootTokenIndex, Constituent subject, List<Constituent> objects, List<Constituent> circumstantials) {
        this(originalText, tokens, rootTokenIndex, subject, objects, circumstantials, -1, -1);
    }

    public SentenceAnalysis {
        originalText = Objects.requireNonNullElse(originalText, "");
        tokens = new ArrayList<>(Objects.requireNonNull(tokens, "tokens"));
        tokens.sort(Comparator.comparingInt(TokenData::index));
        tokens = List.copyOf(tokens);
        objects = Constituent.sorted(Objects.requireNonNullElse(objects, List.of()));
        circumstantials = Constituent.sorted(Objects.requireNonNullElse(circumstantials, List.of()));
        if (beginOffset < -1 || endOffset < -1 || (beginOffset == -1) != (endOffset == -1)
                || (beginOffset >= 0 && endOffset - beginOffset != originalText.length()))
            throw new IllegalArgumentException("Offsets de phrase invalides");
        if (rootTokenIndex < 1) throw new IllegalArgumentException("rootTokenIndex doit être >= 1");
    }

    public Optional<Constituent> subjectOpt() {
        return Optional.ofNullable(subject);
    }

    public TokenData token(int index) {
        return findToken(index).orElseThrow(() -> new IllegalArgumentException("Token introuvable: " + index));
    }

    public Optional<TokenData> findToken(int index) {
        return tokens.stream().filter(token -> token.index() == index).findFirst();
    }

    public List<TokenData> dependentsOf(int headIndex) {
        return tokens.stream().filter(token -> token.headIndex() == headIndex).toList();
    }

    public List<TokenData> dependentsWithRelationPrefix(int headIndex, String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return dependentsOf(headIndex).stream().filter(token -> token.dependency().startsWith(prefix)).toList();
    }

    public List<TokenData> tokensOf(Constituent constituent) {
        return constituent.tokenIndices().stream().map(this::token).toList();
    }

    public int firstLexicalIndex() {
        return tokens.stream().filter(t -> !t.isPunctuation()).mapToInt(TokenData::index).min().orElse(1);
    }

    public int lastLexicalIndex() {
        return tokens.stream().filter(t -> !t.isPunctuation()).mapToInt(TokenData::index).max().orElse(rootTokenIndex);
    }
}
