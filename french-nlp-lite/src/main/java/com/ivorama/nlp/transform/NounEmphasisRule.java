package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.LexiconMorphologicalEngine;
import com.ivorama.nlp.core.MorphologicalEngine;
import com.ivorama.nlp.core.MorphologicalFeatures;
import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Ajout d'un adjectif configuré sur un nom cible explicite. */
public final class NounEmphasisRule implements SentenceRule {
    public enum Position { BEFORE_NOUN, AFTER_NOUN }
    public record Adjective(String lemma, Position position) {
        public Adjective {
            Objects.requireNonNull(lemma);
            Objects.requireNonNull(position);
            if (lemma.isBlank()) throw new IllegalArgumentException("Lemme adjectival vide");
        }
    }

    private final int nounIndex;
    private final List<Adjective> adjectives;
    private final MorphologicalEngine morphology;
    private final int priority;

    public NounEmphasisRule(int nounIndex, List<Adjective> adjectives,
                            MorphologicalEngine morphology, int priority) {
        this.nounIndex = nounIndex;
        this.adjectives = List.copyOf(adjectives);
        this.morphology = Objects.requireNonNull(morphology);
        this.priority = priority;
    }

    @Override
    public List<TextEdit> edits(SentenceAnalysis sentence) {
        var noun = sentence.findToken(nounIndex).orElse(null);
        if (noun == null || !noun.isNoun() || Set.of("PROPN", "NPP").contains(noun.pos())
                || !TextEdit.hasSurface(sentence, noun) || LexicalSafety.isProtectedExpression(sentence, noun)
                || GrammaticalScope.quoted(sentence, noun) || GrammaticalScope.operator(noun)
                || GrammaticalScope.localOperator(sentence, noun)) {
            return List.of();
        }
        var features = morphology.analyze(noun);
        if (!features.hasAgreementEvidence()) return List.of();
        var attached = sentence.dependentsWithRelationPrefix(nounIndex, "amod");
        for (var adjective : adjectives) {
            if (attached.stream().map(morphology::analyze)
                    .anyMatch(existing -> existing.lemma().equalsIgnoreCase(adjective.lemma()))) continue;
            var target = new MorphologicalFeatures(adjective.lemma(), "ADJ", features.gender(), features.number());
            var form = morphology.inflect(adjective.lemma(), target);
            if (form.isEmpty() || form.get().isBlank()) continue;
            if (adjective.position() == Position.BEFORE_NOUN) {
                var edit = before(sentence, noun, form.get());
                if (!edit.isEmpty()) return edit;
            } else {
                return after(sentence, noun, attached, form.get());
            }
        }
        return List.of();
    }

    private List<TextEdit> before(SentenceAnalysis sentence, TokenData noun, String form) {
        String adjective = form;
        String nounText = noun.word();
        boolean first = sentence.firstLexicalIndex() == nounIndex;
        boolean caps = nounText.equals(nounText.toUpperCase(Locale.ROOT));
        // Without an allomorph model, avoid anteposition near an elision or liaison.
        if (uncertainOnset(nounText) || sentence.tokens().stream()
                .anyMatch(token -> token.index() == nounIndex - 1
                    && token.pos().equals("DET") && uncertainOnset(form))) return List.of();
        if (first || caps) adjective = LexiconMorphologicalEngine.caseLike(nounText, form);
        if (first && !caps && Character.isUpperCase(nounText.codePointAt(0))) {
            int length = Character.charCount(nounText.codePointAt(0));
            nounText = nounText.substring(0, length).toLowerCase(Locale.ROOT) + nounText.substring(length);
        }
        return List.of(new TextEdit(noun.beginOffset(), noun.endOffset(), adjective + " " + nounText, priority, "noun-emphasis", Optional.empty()));
    }

    private static boolean uncertainOnset(String word) {
        return NominalAgreement.vowel(word) || word.matches("(?iu)^[hy].*");
    }

    private List<TextEdit> after(SentenceAnalysis sentence, TokenData noun, List<TokenData> adjectives, String form) {
        var anchor = noun;
        for (var token : adjectives) {
            if (token.index() <= nounIndex) continue;
            if (token.index() != anchor.index() + 1 || !TextEdit.hasSurface(sentence, token)) return List.of();
            anchor = token;
        }
        String original = sentence.originalText().substring(noun.beginOffset(), anchor.endOffset());
        String adjective = noun.word().equals(noun.word().toUpperCase(Locale.ROOT))
                ? LexiconMorphologicalEngine.caseLike(noun.word(), form) : form;
        return List.of(new TextEdit(noun.beginOffset(), anchor.endOffset(), original + " " + adjective, priority, "noun-emphasis", Optional.empty()));
    }
}
