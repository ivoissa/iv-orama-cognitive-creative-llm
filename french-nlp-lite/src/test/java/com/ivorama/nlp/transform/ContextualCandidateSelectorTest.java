package com.ivorama.nlp.transform;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContextualCandidateSelectorTest {
    private final ContextualCandidateSelector selector = new ContextualCandidateSelector();
    private static OmwFrenchSynonymProvider.RankedCandidate c(String word, String sense,
            double neighborhood, double lesk, double specificity) {
        return new OmwFrenchSynonymProvider.RankedCandidate(word,sense,neighborhood,lesk,specificity,
                neighborhood+lesk+specificity);
    }
    @Test void emptyIsAbstention() {
        assertEquals(ContextualCandidateSelector.Verdict.NO_CONTEXT_EVIDENCE,selector.evaluate(List.of()).verdict());
    }
    @Test void specificityAloneCannotDisambiguate() {
        assertEquals(ContextualCandidateSelector.Verdict.NO_CONTEXT_EVIDENCE,
                selector.evaluate(List.of(c("alpha","s1",0,0,0.9))).verdict());
    }
    @Test void tiesAcrossSensesRemainAmbiguous() {
        assertEquals(ContextualCandidateSelector.Verdict.AMBIGUOUS,
                selector.evaluate(List.of(c("alpha","s1",1,0,0.3),c("beta","s2",1,0,0.3))).verdict());
    }
    @Test void contextEvidenceDistinguishesSenses() {
        var result=selector.evaluate(List.of(c("alpha","s1",2,1,0.2),c("beta","s2",0,0,0.5)));
        assertEquals(ContextualCandidateSelector.Verdict.CONTEXT_SUPPORTED,result.verdict());
        assertEquals("alpha",result.best().orElseThrow().word());
    }
    @Test void twoWordsInSameSenseDoNotCreateFalseAmbiguity() {
        var result=selector.evaluate(List.of(c("alpha","s1",1,0,0.4),c("beta","s1",1,0,0.4)));
        assertEquals(ContextualCandidateSelector.Verdict.CONTEXT_SUPPORTED,result.verdict());
    }
}
