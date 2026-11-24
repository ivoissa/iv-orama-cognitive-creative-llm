package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ContextCacheIsolationTest {
    @Test void repeatsAnAssessmentOnlyWithinTheSameDocument(@TempDir Path path) {
        var token = new TokenData(1, "avocat", "NOUN");
        var legal = new SentenceAnalysis("Avocat au tribunal.", List.of(token), 1, null, null, null);
        var fruit = new SentenceAnalysis("Avocat dans la salade.", List.of(token), 1, null, null, null);
        var calls = new AtomicInteger();
        var delegate = new SynonymProvider() {
            private List<SentenceAnalysis> document;
            public void prepareDocument(List<SentenceAnalysis> analyses) { document = List.copyOf(analyses); }
            public Optional<String> synonym(TokenData token, SynonymRegister register) { return Optional.empty(); }
            public CandidateDecision assess(TokenData token, SynonymRegister register, String candidate, SentenceAnalysis context) {
                calls.incrementAndGet();
                return document.contains(fruit) ? CandidateDecision.reject("CULINARY_CONTEXT") : CandidateDecision.trusted();
            }
        };
        var cache = new CachingSynonymProvider(delegate, path.resolve("cache.tsv"), false, false);
        cache.prepareDocument(List.of(legal));
        for (int i = 0; i < 10; i++) assertTrue(cache.assess(token, SynonymRegister.COURANT, "juriste", legal).allowed());
        assertEquals(1, calls.get());
        cache.prepareDocument(List.of(legal, fruit));
        assertFalse(cache.assess(token, SynonymRegister.COURANT, "juriste", legal).allowed());
        assertEquals(2, calls.get());
        assertFalse(cache.assess(token, SynonymRegister.COURANT, "juriste", fruit).allowed());
        assertEquals(3, calls.get());
        assertFalse(java.nio.file.Files.exists(path.resolve("cache.tsv")));
    }
}
