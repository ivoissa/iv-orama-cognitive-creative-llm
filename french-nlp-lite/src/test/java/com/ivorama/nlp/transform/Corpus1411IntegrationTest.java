package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.FrenchNlpAnalyzer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Corpus de six phrases originales, sans figer une unique reformulation. */
class Corpus1411IntegrationTest {
    private static final String CORPUS = """
        Hier soir, dans le jardin silencieux, une astronome observait les étoiles avec son collègue.
        Le lendemain, elle présenta ses résultats à une équipe attentive.
        Près de la rivière, les enfants découvrirent une vieille pierre, puis ils la laissèrent sur place.
        « Ce phénomène est étrange », déclara le chercheur, sans modifier ses mesures.
        À Paris, l'équipe rédigea un rapport précis ; pourtant, plusieurs questions restaient ouvertes.
        Enfin, lorsque la pluie cessa, les visiteurs quittèrent tranquillement le musée.
        """;
    @Test void sixSentencesNoKnownCorruptions() {
        var service = new SentenceTransformerService(new FrenchNlpAnalyzer(),
                SynonymProviderFactory.create(LexicalConfig.defaults()));
        var config = new TransformationConfig(false,true,false,true,false,true,true,false,20261107L);
        var result = service.transformText(CORPUS,config);
        assertEquals(6,result.sentences().size(),"Découpage des six phrases");
        String text = result.transformed().toLowerCase(java.util.Locale.ROOT);
        assertFalse(text.contains("la fleuve"));
        assertFalse(text.contains("une vieille bite"));
        assertFalse(text.contains("une cabine attentive"));
        assertFalse(text.contains("un abord précis"));
        assertFalse(text.contains(";."));
        assertFalse(text.contains("après-midi, hier"));
        assertTrue(result.sentences().stream().anyMatch(r -> r.appliedRules().contains("yoda_mode")),
                "Le mode Yoda demandé doit rester actif");
    }
}
