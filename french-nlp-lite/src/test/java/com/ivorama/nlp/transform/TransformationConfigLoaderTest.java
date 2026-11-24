package com.ivorama.nlp.transform;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TransformationConfigLoaderTest {
    @Test
    void loadsRequestedFlatYaml() {
        String yaml = """
                transformations:
                  synonym_register:
                    familiar_enabled: false
                    common_enabled: false
                    formal_enabled: true
                  yoda_mode_enabled: true
                  replace_adjectives_with_synonyms_enabled: oui
                  move_circumstantial_complements_enabled: 1
                  add_commas_around_complements_enabled: yes
                  stoic_mode_enabled: false
                  random_seed: 42
                """;
        var c = TransformationConfigLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(SynonymRegister.SOUTENU, c.requestedRegister());
        assertTrue(c.yodaMode());
        assertTrue(c.emphaseColores());
        assertEquals(42L, c.randomSeed());
    }

    @Test
    void tieFallsBackToCourant() {
        var c = new TransformationConfig(true, true, true, false, false, false, true, false, 1);
        assertEquals(SynonymRegister.COURANT, c.requestedRegister());
    }

    @Test
    void singleRegisterRemainsSelectable() {
        assertEquals(SynonymRegister.FAMILIER,
                new TransformationConfig(true, false, false, false, false, false, false, false, 42L).requestedRegister());
        assertEquals(SynonymRegister.SOUTENU,
                new TransformationConfig(false, false, true, false, false, false, false, false, 42L).requestedRegister());
        assertEquals(SynonymRegister.COURANT,
                new TransformationConfig(false, false, false, false, false, false, false, false, 42L).requestedRegister());
    }

    @Test
    void registerTieAlwaysFallsBackToCourant() {
        assertEquals(SynonymRegister.COURANT,
                new TransformationConfig(true, true, false, false, false, false, false, false, 42L).requestedRegister());
        assertEquals(SynonymRegister.COURANT,
                new TransformationConfig(false, true, true, false, false, false, false, false, 42L).requestedRegister());
        assertEquals(SynonymRegister.COURANT,
                new TransformationConfig(true, false, true, false, false, false, false, false, 42L).requestedRegister());
    }

    @Test
    void rejectsUnknownYamlKey() {
        String yaml = "transformations:\n  yoda_mode_enabled: true\n  mode_invente: true\n";
        assertThrows(IllegalArgumentException.class, () -> TransformationConfigLoader.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsNonBooleanEnergy() {
        String yaml = "transformations:\n  synonym_register:\n    familiar_enabled: 101\n";
        assertThrows(IllegalArgumentException.class, () -> TransformationConfigLoader.parse(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))));
    }
}
