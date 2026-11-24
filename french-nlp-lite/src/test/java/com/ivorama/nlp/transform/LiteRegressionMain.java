package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.TokenData;

import java.util.Optional;

/**
 * Suite sans JUnit/CoreNLP pour valider le moteur pur dans un environnement hors ligne.
 * Elle est aussi compilable par Maven avec le reste des tests.
 */
public final class LiteRegressionMain {
    private static int passed = 0;

    public static void main(String[] args) {
        allModesExceptStoique();
        firstAndThirdNouns();
        yodaCodCoi();
        joyeuseCc();
        respirationOnly();
        threeAdjectivesAll();
        moreThanThreeHalf();
        stoiqueOverride();
        registerFallback();
        configTie();
        yamlDefaultReallyActivatesAllExceptStoique();
        yamlRejectsUnknownKey();
        unseenSentenceNoHardcoding();
        System.out.println("LITE REGRESSION: " + passed + "/13 tests OK");
    }

    private static TransformationConfig cfg(boolean yoda, boolean emphase, boolean joyeuse,
                                             boolean respiration, boolean stoique) {
        return new TransformationConfig(true, true, true, yoda, emphase, joyeuse, respiration, stoique, 20261107L);
    }

    private static void allModesExceptStoique() {
        var r = engine().transform(TestFixtures.richSentence(), TransformationConfigLoader.loadDefault());
        eq("Rapidement, la pâtée au canidé le petit félin sombre curieux vorace apporte, silencieusement, dans le jardin.", r.transformed(), "all modes");
        eq(SynonymRegister.COURANT, r.effectiveRegister(), "fallback current");
        ok(r.replacements().size() == 4, "4 replacements");
        pass();
    }

    private static void firstAndThirdNouns() {
        var r = engine().transform(TestFixtures.yodaSimple(), cfg(false,false,false,false,false));
        eq("Le félin donne la pâtée au canidé.", r.transformed(), "noun 1/3"); pass();
    }

    private static void yodaCodCoi() {
        var r = emptyEngine().transform(TestFixtures.yodaSimple(), cfg(true,false,false,false,false));
        eq("La pâtée au chien le chat donne.", r.transformed(), "yoda"); pass();
    }

    private static void joyeuseCc() {
        var r = emptyEngine().transform(TestFixtures.circumstancesNoCommas(), cfg(false,false,true,true,false));
        eq("Rapidement, le chat mange, silencieusement, sa pâtée, dans le jardin.", r.transformed(), "joyeuse"); pass();
    }

    private static void respirationOnly() {
        var r = emptyEngine().transform(TestFixtures.circumstancesNoCommas(), cfg(false,false,false,true,false));
        eq("Le chat mange, rapidement, sa pâtée, silencieusement, dans le jardin.", r.transformed(), "respiration"); pass();
    }

    private static void threeAdjectivesAll() {
        var r = engine().transform(TestFixtures.threeAdjectives(), cfg(false,true,false,false,false));
        eq("Le minuscule félin sombre intrigué voit la souris.", r.transformed(), "3 adjectives");
        ok(r.replacements().stream().filter(x -> x.reason().equals("adjectif/emphase")).count() == 3, "all 3 adjectives"); pass();
    }

    private static void moreThanThreeHalf() {
        var r = engine().transform(TestFixtures.richSentence(), cfg(false,true,false,false,false));
        ok(r.replacements().stream().filter(x -> x.reason().equals("adjectif/emphase")).count() == 2, "half adjectives"); pass();
    }

    private static void stoiqueOverride() {
        var r = engine().transform(TestFixtures.circumstancesNoCommas(), cfg(true,true,true,true,true));
        eq("Le chat mange, rapidement, sa pâtée, silencieusement, dans le jardin.", r.transformed(), "stoique");
        ok(r.replacements().isEmpty(), "stoique no replacements"); pass();
    }

    private static void registerFallback() {
        var r = engine().transform(TestFixtures.yodaSimple(), new TransformationConfig(true,false,false,false,false,false,false,false,1));
        eq(SynonymRegister.FAMILIER, r.requestedRegister(), "requested register");
        eq(SynonymRegister.COURANT, r.effectiveRegister(), "effective current"); pass();
    }

    private static void configTie() {
        var c = new TransformationConfig(true, true, true,false,false,false,true,false,1);
        eq(SynonymRegister.COURANT, c.requestedRegister(), "tie current"); pass();
    }


    private static void yamlDefaultReallyActivatesAllExceptStoique() {
        var c = TransformationConfigLoader.loadDefault();
        ok(c.yodaMode(), "yaml yoda");
        ok(c.emphaseColores(), "yaml emphase");
        ok(c.joyeuseCirconstanceMode(), "yaml joyeuse");
        ok(c.respirationCoolMode(), "yaml respiration");
        ok(!c.stoiquePhiloMode(), "yaml stoique off");
        ok(c.synonymeFamilierEnergie() && c.synonymeCourantEnergie() && c.synonymeSoutenuEnergie(),
                "yaml all synonym energies active");
        pass();
    }

    // Test métamorphique : aucune sortie prédéfinie; le moteur doit préserver
    // une substitution lexicale inédite et la réordonner avec les mêmes règles.
    private static void unseenSentenceNoHardcoding() {
        var source = TestFixtures.yodaSimple();
        var altered = source.tokens().stream().map(t -> t.index() == 3
                ? new TokenData(t.index(), "transporte", t.pos()) : t).toList();
        var input = new com.ivorama.nlp.core.SentenceAnalysis(
                "Le chat transporte la pâtée au chien.", altered, source.rootTokenIndex(),
                source.subject(), source.objects(), source.circumstantials());
        var output = emptyEngine().transform(input, cfg(true,false,false,false,false)).transformed();
        ok(output.contains("transporte"), "unseen verb must survive");
        ok(output.indexOf("transporte") > output.indexOf("chat"), "yoda position on unseen sentence");
        ok(!output.contains("donne"), "must not reuse fixture output");
        pass();
    }

    private static void yamlRejectsUnknownKey() {
        String yaml = "transformations:\n  yoda_mode_enabled: true\n  mode_invente: true\n";
        try {
            TransformationConfigLoader.parse(new java.io.ByteArrayInputStream(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            throw new AssertionError("unknown yaml key should fail");
        } catch (IllegalArgumentException expected) {
            pass();
        }
    }

    private static SentenceTransformerEngine engine() { return new SentenceTransformerEngine(new ResourceSynonymProvider()); }
    private static SentenceTransformerEngine emptyEngine() {
        return new SentenceTransformerEngine(new SynonymProvider() {
            @Override public Optional<String> synonym(TokenData token, SynonymRegister register) { return Optional.empty(); }
        });
    }
    private static void eq(Object expected, Object actual, String name) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(name + " expected=" + expected + " actual=" + actual);
    }
    private static void ok(boolean value, String name) { if (!value) throw new AssertionError(name); }
    private static void pass() { passed++; }
}
