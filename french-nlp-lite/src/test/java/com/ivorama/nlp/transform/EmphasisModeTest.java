package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real CoreNLP analyses and real form dictionary; tiny provider is a test fixture only. */
class EmphasisModeTest {
    static FrenchNlpAnalyzer analyzer;
    static final SynonymProvider FIXTURE = new SynonymProvider() {
        public Optional<String> synonym(TokenData t, SynonymRegister r) { return Optional.of("sombre"); }
        public boolean isCuratedCandidate(TokenData t, SynonymRegister r, String c) { return c.equals("sombre"); }
    };
    @BeforeAll static void init() { analyzer = new FrenchNlpAnalyzer(); }
    static TransformationConfig config(long seed, boolean enabled, boolean stoic) {
        return new TransformationConfig(false,true,false,false,false,false,false,stoic,seed,false,enabled);
    }
    static TextTransformationResult run(String text, long seed) {
        return new SentenceTransformerService(analyzer,FIXTURE).transformText(text,config(seed,true,false));
    }
    static long additions(TextTransformationResult r) {
        return r.sentences().stream().flatMap(s->s.replacements().stream())
            .filter(x->x.reason().equals("adjectif ajouté/emphase_mode")).count();
    }
    static long selected(TextTransformationResult r) {
        return r.sentences().stream().flatMap(s->s.lexicalAttempts().stream())
            .filter(x->x.reason().startsWith("EMPHASIS_")&&!x.status().equals("NOT_SELECTED")).count();
    }
    @Test void keepsAdjectiveAndAddsCoordinatedAdjective() {
        var r=run("Le chat noir dort.",42);
        assertEquals("Le chat noir et sombre dort.",r.transformed()); assertEquals(1,additions(r));
    }
    @Test void keepsAdjectiveWithComplementIntact() {
        String text = "Cet homme était fier de son travail.";
        var result = run(text, 42);
        assertEquals(text, result.transformed());
        assertTrue(result.sentences().stream().flatMap(s -> s.lexicalAttempts().stream())
                .anyMatch(attempt -> attempt.reason().equals("EMPHASIS_ADJECTIVE_COMPLEMENT")));
    }

    @Test void lexicalEvidenceIdentifiesRedundantAdjectives() {
        assertTrue(RobertGroupEvidence.load().redundantAdjective("valide", "valable"));
    }

    @Test void robertRejectsRedundantAndContradictoryAdditions() {
        var provider = SynonymProviderFactory.create(TransformationConfigLoader.defaultPath());
        assertFalse(provider.compatibleAddition(new TokenData(1, "valide", "ADJ"), "valide", "valable", null));
        assertFalse(provider.compatibleAddition(new TokenData(1, "chaud", "ADJ"), "chaud", "froid", null));
    }

    @Test void configuredEmphasisDoesNotAddAnAdjectiveToABareNoun(@org.junit.jupiter.api.io.TempDir Path folder) throws Exception {
        Path yaml = folder.resolve("emphasis.yml");
        java.nio.file.Files.writeString(yaml, """
            transformations:
              emphase_mode: true
              noun_emphasis: "sombre:apres"
              replace_first_and_third_nouns_enabled: false
            """);
        var settings = TransformationConfigLoader.loadGlobal(yaml);
        var service = new SentenceTransformerService(analyzer, SynonymProviderFactory.create(settings.lexical(), settings.weights()));
        var result = service.transformText("Des robes tombent.", settings.transformations());
        assertEquals("Des robes tombent.", result.transformed());
        assertEquals(0L, additions(result));
        assertEquals("Marie ne répond pas.", service.transformText("Marie ne répond pas.", settings.transformations()).transformed());
    }

    @Test void noAdjectiveDoesNotInventOne() { assertEquals("Le chat dort.",run("Le chat dort.",42).transformed()); }
    @Test void upToThreeAcrossSentencesAreAllEmphasized() {
        for(int n=1;n<=3;n++){var r=run("Le chat noir dort. ".repeat(n).strip(),42);assertEquals(n,additions(r));}
    }
    @Test void nineAcrossDocumentSelectsThreeRatherThanOnePerSentence() {
        var r=run("Le chat noir dort. ".repeat(9).strip(),42);assertEquals(3,selected(r));assertEquals(3,additions(r));
    }
    @Test void floorsTheThirdAboveThree() {
        for(int n:new int[]{4,5,6,7,8,10})assertEquals(n/3,additions(run("Le chat noir dort. ".repeat(n).strip(),42)));
    }
    @Test void sampleIsReproducibleAndVariesWithSeed() {
        String text="Le chat noir dort. ".repeat(9).strip();
        assertEquals(run(text,42).transformed(),run(text,42).transformed());
        assertNotEquals(run(text,42).transformed(),run(text,43).transformed());
    }
    @Test void femininePluralAgreement() {
        var r=run("Les robes noires tombent.",42);
        assertEquals("Les robes noires et sombres tombent.",r.transformed());
    }
    @Test void prenominalAdjectiveIsKeptBeforeNoun() {
        assertEquals("Le petit et sombre chat dort.",run("Le petit chat dort.",42).transformed());
    }
    @Test void disabledAndStoicOverride() {
        for(var c:List.of(config(42,false,false),config(42,true,true)))
            assertEquals("Le chat noir dort.",new SentenceTransformerService(analyzer,FIXTURE).transformText("Le chat noir dort.",c).transformed());
    }
    @Test void missingCandidateIsReportedAndDoesNotTriggerRedraw() {
        SynonymProvider empty=(t,r)->Optional.empty();
        var r=new SentenceTransformerService(analyzer,empty).transformText("Le chat noir dort. ".repeat(9).strip(),config(42,true,false));
        assertEquals(3,selected(r));assertEquals(0,additions(r));
        assertEquals(3,r.sentences().stream().flatMap(s->s.lexicalAttempts().stream()).filter(x->x.reason().equals("EMPHASIS_NO_ADMISSIBLE_CANDIDATE")).count());
    }
    @Test void unrelatedDiscourseMarkerDoesNotBlockAddition() {
        var r=run("Toujours le chat noir dort.",42);
        assertEquals(1,additions(r));
        System.out.println("DISCOURSE_EMPHASIS: "+r.transformed());
    }
    @Test void quotesStayProtected() { assertEquals("« Le chat noir dort. »",run("« Le chat noir dort. »",42).transformed()); }
    @Test void actualExternalTsvSuppliesAdditionWithoutEmbeddedWordPair() {
        var service=new SentenceTransformerService(analyzer,new ResourceSynonymProvider());
        var result=service.transformText("Le chat noir dort.",config(42,true,false));
        assertEquals("Le chat noir et sombre dort.",result.transformed());
        assertEquals(1,additions(result));
        System.out.println("EXTERNAL_TSV_EMPHASIS: "+result.transformed());
    }
    @Test void realConfiguredProviderProducesAuditableResults() {
        Path path=Path.of("src/test/resources/profiles/emphase-only.yml");
        var service=new SentenceTransformerService(analyzer,SynonymProviderFactory.create(path));
        for(String text:List.of("Un homme grand marche.","Un ciel bleu apparaît.","Le chat noir dort.","La mer calme brille.")) {
            var r=service.transformText(text,TransformationConfigLoader.load(path));
            System.out.println("REAL_EMPHASIS: "+text+" => "+r.transformed());
            r.sentences().forEach(s->s.lexicalAttempts().forEach(a->System.out.println("  "+a.status()+" "+a.reason()+" "+a.selected()+" "+a.checks())));
            assertEquals(1,selected(r));
        }
    }
}
