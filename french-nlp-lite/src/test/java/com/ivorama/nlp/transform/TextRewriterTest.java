package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextRewriterTest {
    private final LexiconMorphologicalEngine lexicon = new LexiconMorphologicalEngine();

    private static TokenData token(int i, String word, String pos, int begin, int head, String relation) {
        return new TokenData(i, word, pos, begin, begin + word.length(), "", "", "", head, relation);
    }

    private static SentenceAnalysis sentence(String text, int root, TokenData... tokens) {
        return new SentenceAnalysis(text, List.of(tokens), root, null, List.of(), List.of(), 100, 100 + text.length());
    }

    @Test void offsetsStayRelativeAndDependencyNavigationIsOrdered() {
        var noun = token(3, "vélo", "NOUN", 9, 4, "nsubj");
        var det = token(1, "Un", "DET", 0, 3, "det");
        var adjective = token(2, "grand", "ADJ", 3, 3, "amod");
        var analysis = sentence("Un grand vélo roule.", 4, noun, det, adjective);
        assertEquals(List.of(det, adjective), analysis.dependentsOf(3));
        assertEquals(List.of(adjective), analysis.dependentsWithRelationPrefix(3, "amod"));
        assertEquals(List.of(det, noun), analysis.tokensOf(new Constituent(ConstituentRole.SUBJECT, List.of(3, 1), "nsubj")));
        assertEquals(100, analysis.beginOffset());
        assertEquals(9, noun.beginOffset());
        assertThrows(IllegalArgumentException.class,
                () -> new SentenceAnalysis("vélo", List.of(), 1, null, List.of(), List.of(), 100, 103));
    }

    @Test void appliesFromRightToLeftAndRejectsAnEntireConflictingPlan() {
        var analysis = sentence("Un vélo roule.", 3);
        SentenceRule low = ignored -> List.of(new TextEdit(0, 2, "Une", 1), new TextEdit(3, 7, "bicyclette", 1));
        SentenceRule high = ignored -> List.of(new TextEdit(3, 7, "cycle", 2));
        SentenceRule append = ignored -> List.of(new TextEdit(13, 13, " vite", 0));
        assertEquals("Un cycle roule vite.", new TextRewriter(List.of(low, append, high)).rewrite(analysis));
    }

    @Test void exposesAcceptedRulesAndAppliesSourceCase() {
        var analysis = sentence("VÉLO.", 1, token(1, "VÉLO", "NOUN", 0, 0, "root"));
        var rule = new TextRewriter(List.of(source -> List.of(new TextEdit(0, 4, "cycle", 2,
                "substitution", Optional.of(source.token(1))))));
        assertEquals("CYCLE.", rule.rewrite(analysis));
        assertEquals("substitution", rule.plan(analysis).get(0).ruleName());
        assertTrue(analysis.findToken(2).isEmpty());
    }

    @Test void insertsAnAttestedAdjectiveBeforeOrAfterTheNoun() {
        var analysis = sentence("Une robe tombe.", 3,
                token(1, "Une", "DET", 0, 2, "det"), token(2, "robe", "NOUN", 4, 3, "nsubj"));
        var before = new NounEmphasisRule(2, List.of(new NounEmphasisRule.Adjective("grand",
                NounEmphasisRule.Position.BEFORE_NOUN)), lexicon, 1);
        var after = new NounEmphasisRule(2, List.of(new NounEmphasisRule.Adjective("sombre",
                NounEmphasisRule.Position.AFTER_NOUN)), lexicon, 1);
        assertEquals("Une grande robe tombe.", new TextRewriter(List.of(before)).rewrite(analysis));
        assertEquals("Une robe sombre tombe.", new TextRewriter(List.of(after)).rewrite(analysis));
        assertEquals(4, before.edits(analysis).get(0).beginOffset());
        assertEquals(8, after.edits(analysis).get(0).endOffset());
        var withAdjective = sentence("Une robe noire tombe.", 4, token(1, "Une", "DET", 0, 2, "det"),
                token(2, "robe", "NOUN", 4, 4, "nsubj"), token(3, "noire", "ADJ", 9, 2, "amod"));
        assertEquals("Une robe noire sombre tombe.", new TextRewriter(List.of(after)).rewrite(withAdjective));
    }

    @Test void skipsWhenFlexionOrOffsetsAreUnavailable() {
        var noun = token(2, "robe", "NOUN", 4, 3, "nsubj");
        var analysis = sentence("Une robe tombe.", 3, noun);
        var missing = new NounEmphasisRule(2, List.of(new NounEmphasisRule.Adjective("zzinconnuq",
                NounEmphasisRule.Position.AFTER_NOUN)), lexicon, 1);
        assertTrue(missing.edits(analysis).isEmpty());
        var conservative = new ConservativeMorphologicalEngine();
        assertTrue(conservative.inflect("sombre", new MorphologicalFeatures("autre", "ADJ", "fem", "pl")).isEmpty());
        var rule = new NounEmphasisRule(2, List.of(new NounEmphasisRule.Adjective("grand",
                NounEmphasisRule.Position.BEFORE_NOUN)), conservative, 1);
        assertTrue(rule.edits(analysis).isEmpty());
        assertTrue(missing.edits(sentence("Une robe tombe.", 3, new TokenData(2, "robe", "NOUN"))).isEmpty());
    }

    @Test void replacesNounAndAgreesDeterminerAndAdjectiveAsOneEditPlan() {
        var det = token(1, "Un", "DET", 0, 2, "det");
        var noun = token(2, "vélo", "NOUN", 3, 4, "nsubj");
        var adj = token(3, "neuf", "ADJ", 8, 2, "amod");
        var provider = new SynonymProvider() {
            public Optional<String> synonym(TokenData token, SynonymRegister register) {
                return Optional.of("bicyclette");
            }
            public boolean isCuratedCandidate(TokenData token, SynonymRegister register, String candidate) {
                return candidate.equals("bicyclette");
            }
        };
        var rule = new NounEquivalentRule(2, provider, SynonymRegister.COURANT, lexicon, 1);
        assertEquals("Une bicyclette neuve roule.", new TextRewriter(List.of(rule))
                .rewrite(sentence("Un vélo neuf roule.", 4, det, noun, adj, token(4, "roule", "VERB", 13, 0, "root"))));
        var unknown = token(3, "zzinconnuq", "ADJ", 8, 2, "amod");
        assertTrue(rule.edits(sentence("Un vélo zzinconnuq roule.", 4, det, noun, unknown, token(4, "roule", "VERB", 19, 0, "root"))).isEmpty());
    }
}
