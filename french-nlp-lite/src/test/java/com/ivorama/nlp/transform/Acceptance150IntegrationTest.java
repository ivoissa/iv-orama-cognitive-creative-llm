package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.FrenchNlpAnalyzer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** À exécuter avec Maven et les modèles français CoreNLP sur la machine de validation. */
class Acceptance150IntegrationTest {
    @Test void transformationSurCorpusIndependantAvecTraces() {
        String text = """
            Le médecin examine le patient dans son cabinet.
            La banque finance une entreprise locale.
            Au bord du fleuve, les promeneurs admirent les oiseaux.
            La chercheuse présente une étude à ses collègues.
            Pendant la nuit, le vent secoue les arbres.
            Les enfants rangent leurs livres dans la bibliothèque.
            """;
        var provider = SynonymProviderFactory.create(LexicalConfig.defaults());
        var service = new SentenceTransformerService(new FrenchNlpAnalyzer(), provider);
        var config = new TransformationConfig(false,true,false,true,false,true,true,false,20261110L);
        var result = service.transformText(text,config);
        System.out.println("=== CORPUS INDEPENDANT, ORIGINAL ===\n" + text);
        System.out.println("=== SORTIE JAVA ===\n" + result.transformed());
        result.sentences().forEach(r -> System.out.println("PHRASE: " + r));
        assertEquals(6,result.sentences().size(),"Six phrases analysées et transformées");
        assertFalse(result.transformed().isBlank());
        assertFalse(result.transformed().contains(";."),"Ponctuation non corrompue");
    }
}
