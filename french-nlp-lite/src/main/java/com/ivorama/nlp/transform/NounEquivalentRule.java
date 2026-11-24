package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.MorphologicalEngine;
import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Substitution et accords constituent une édition atomique. */
public final class NounEquivalentRule implements SentenceRule {
    private final int nounIndex;
    private final SynonymProvider provider;
    private final SynonymRegister register;
    private final NominalAgreement agreement;
    private final MorphologicalEngine morphology;
    private final int priority;

    public NounEquivalentRule(int nounIndex, SynonymProvider provider, SynonymRegister register,
                              MorphologicalEngine morphology, int priority) {
        this.nounIndex = nounIndex;
        this.provider = Objects.requireNonNull(provider);
        this.register = Objects.requireNonNull(register);
        this.morphology = Objects.requireNonNull(morphology);
        this.agreement = new NominalAgreement(this.morphology);
        this.priority = priority;
    }

    @Override
    public List<TextEdit> edits(SentenceAnalysis sentence) {
        var noun = sentence.findToken(nounIndex).orElse(null);
        if (noun == null || !noun.isNoun() || LexicalSafety.isProtectedExpression(sentence, noun)
                || GrammaticalScope.quoted(sentence, noun) || GrammaticalScope.operator(noun)
                || GrammaticalScope.localOperator(sentence, noun)) return List.of();
        var words = sentence.tokens().stream().collect(Collectors.toMap(TokenData::index, t ->
                t.beginOffset() >= 0 && t.endOffset() <= sentence.originalText().length()
                    ? sentence.originalText().substring(t.beginOffset(), t.endOffset()) : t.word()));
        for (var candidate : provider.candidates(noun, register, sentence)) {
            if (!provider.assess(noun, register, candidate, sentence).allowed()
                    || ContextualGuard.introducesDuplicate(sentence, noun, candidate, words, morphology)) continue;
            var plan = agreement.plan(sentence, noun, candidate, words, true);
            if (plan.isEmpty()) continue;
            var changes = plan.get().words().entrySet().stream()
                    .filter(entry -> !entry.getValue().equals(words.get(entry.getKey()))).toList();
            if (changes.stream().anyMatch(entry -> !TextEdit.hasSurface(sentence, sentence.token(entry.getKey())))) continue;
            return changes.stream().map(entry -> TextEdit.replace(sentence, sentence.token(entry.getKey()),
                    entry.getValue(), priority, "noun-equivalent")).toList();
        }
        return List.of();
    }
}
