package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SentenceTransformerEngineTest {
    private static TransformationConfig cfg(boolean yoda, boolean emphase, boolean joyeuse,
                                             boolean respiration, boolean stoique) {
        return new TransformationConfig(true, true, true, yoda, emphase, joyeuse, respiration, stoique, 20261107L);
    }

    @Test
    void firstScenarioActivatesEverythingExceptStoique() {
        var result = new SentenceTransformerEngine(new ResourceSynonymProvider())
                .transform(TestFixtures.richSentence(), cfg(true, true, true, true, false));

        assertEquals("Dans le jardin, la pâtée au canidé le petit félin sombre curieux vorace apporte rapidement, silencieusement.",
                result.transformed());
        assertEquals(SynonymRegister.COURANT, result.requestedRegister());
        assertEquals(SynonymRegister.COURANT, result.effectiveRegister(),
                "Le fournisseur livré ne garantit pas les registres: repli courant obligatoire");
        assertEquals(4, result.replacements().size());
        assertTrue(result.appliedRules().contains("yoda_mode"));
        assertTrue(result.appliedRules().contains("joyeuse_circonstance_mode"));
        assertTrue(result.appliedRules().contains("respiration_cool_mode"));
    }

    @Test
    void firstAndThirdNounsOnlyAreReplaced() {
        var result = new SentenceTransformerEngine(new ResourceSynonymProvider())
                .transform(TestFixtures.yodaSimple(), cfg(false, false, false, false, false));
        assertEquals("Le félin donne la pâtée au canidé.", result.transformed());
        assertEquals(2, result.replacements().size());
        assertEquals(2, result.replacements().get(0).tokenIndex());
        assertEquals(8, result.replacements().get(1).tokenIndex());
    }

    @Test
    void yodaMovesCodAndCoiBeforeSubject() {
        var result = new SentenceTransformerEngine(new EmptySynonyms())
                .transform(TestFixtures.yodaSimple(), cfg(true, false, false, false, false));
        assertEquals("La pâtée au chien le chat donne.", result.transformed());
    }

    @Test
    void joyeuseMovesThePrepositionalComplementAndLeavesIsolatedAdverbs() {
        var result = new SentenceTransformerEngine(new EmptySynonyms())
                .transform(TestFixtures.circumstancesNoCommas(), cfg(false, false, true, true, false));
        assertEquals("Dans le jardin, le chat mange rapidement sa pâtée silencieusement.", result.transformed());
    }

    @Test
    void respirationPunctuatesCcsWithoutMovingThem() {
        var result = new SentenceTransformerEngine(new EmptySynonyms())
                .transform(TestFixtures.circumstancesNoCommas(), cfg(false, false, false, true, false));
        assertEquals("Le chat mange rapidement sa pâtée silencieusement, dans le jardin.", result.transformed());
    }

    @Test
    void threeAdjectivesMeansAllThreeAreReplaced() {
        var result = new SentenceTransformerEngine(new ResourceSynonymProvider())
                .transform(TestFixtures.threeAdjectives(), cfg(false, true, false, false, false));
        assertEquals("Le minuscule félin sombre intrigué voit la souris.", result.transformed());
        long adjectiveReplacements = result.replacements().stream()
                .filter(r -> r.reason().equals("adjectif/emphase")).count();
        assertEquals(3, adjectiveReplacements);
    }

    @Test
    void moreThanThreeAdjectivesMeansDeterministicRandomHalf() {
        var result = new SentenceTransformerEngine(new ResourceSynonymProvider())
                .transform(TestFixtures.richSentence(), cfg(false, true, false, false, false));
        long adjectiveReplacements = result.replacements().stream()
                .filter(r -> r.reason().equals("adjectif/emphase")).count();
        assertEquals(2, adjectiveReplacements);
        assertTrue(result.transformed().contains("sombre"));
        assertTrue(result.transformed().contains("vorace"));
    }

    @Test
    void stoiqueOverridesEveryOtherModeAndKeepsOnlyRespiration() {
        var result = new SentenceTransformerEngine(new ResourceSynonymProvider())
                .transform(TestFixtures.circumstancesNoCommas(), cfg(true, true, true, true, true));
        assertEquals("Le chat mange rapidement sa pâtée silencieusement, dans le jardin.", result.transformed());
        assertTrue(result.replacements().isEmpty());
        assertEquals(java.util.List.of("stoique_philo_mode_override", "respiration_cool_mode"), result.appliedRules());
    }

    @Test
    void registerAwareProviderReceivesRequestedRegister() {
        class Aware implements SynonymProvider {
            SynonymRegister seen;
            @Override public Optional<String> synonym(TokenData token, SynonymRegister register) {
                seen = register;
                return Optional.empty();
            }
            @Override public boolean supportsRegisters() { return true; }
        }
        Aware aware = new Aware();
        var result = new SentenceTransformerEngine(aware)
                .transform(TestFixtures.yodaSimple(), cfg(false, false, false, false, false));
        assertEquals(SynonymRegister.COURANT, aware.seen);
        assertEquals(SynonymRegister.COURANT, result.effectiveRegister());
    }

    private static final class EmptySynonyms implements SynonymProvider {
        @Override public Optional<String> synonym(TokenData token, SynonymRegister register) {
            return Optional.empty();
        }
    }
}
