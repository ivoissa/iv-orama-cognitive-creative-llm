package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cas indépendants du lexique et du modèle CoreNLP. */
class ClauseBoundaryRegressionTest {
    private final SynonymProvider empty = (token, register) -> Optional.empty();
    private final TransformationConfig config = new TransformationConfig(false, true, false, true, false, true, true, false, 17);

    @Test void keepsSemicolonBetweenClauses() {
        assertEquals("Le jardinier arrose les fleurs ; ensuite, il rentre.",
                SentenceRenderer.render(List.of("Le", "jardinier", "arrose", "les", "fleurs", ";", "ensuite", ",", "il", "rentre", ".")));
    }

    @Test void alreadyFrontedCircumstantialIsNotMovedOrClaimed() {
        var tokens = List.of(new TokenData(1,"Chaque","DET"),new TokenData(2,"matin","NOUN"),
                new TokenData(3,",","PUNCT"),new TokenData(4,"une","DET"),
                new TokenData(5,"infirmière","NOUN"),new TokenData(6,"accueille","VERB"),
                new TokenData(7,"les","DET"),new TokenData(8,"patients","NOUN"),new TokenData(9,".","PUNCT"));
        var cc = Constituent.of(ConstituentRole.CIRCUMSTANTIAL, "obl", List.of(1,2));
        var analysis = new SentenceAnalysis("Chaque matin, une infirmière accueille les patients.", tokens,6,null,List.of(),List.of(cc));
        var result = new SentenceTransformerEngine(empty).transform(analysis,config);
        assertEquals("Chaque matin, une infirmière accueille les patients.", result.transformed());
        assertFalse(result.appliedRules().contains("joyeuse_circonstance_mode"));
    }
    @Test void movedComplementKeepsDetachedVocativeComma() {
        var tokens = List.of(new TokenData(1,"Il","PRON"),new TokenData(2,"travaille","VERB"),
            new TokenData(3,"chaque","DET"),new TokenData(4,"soir","NOUN"),
            new TokenData(5,",","PUNCT"),new TokenData(6,"Paul","PROPN"),new TokenData(7,".","PUNCT"));
        var cc=Constituent.of(ConstituentRole.CIRCUMSTANTIAL,"obl",List.of(3,4));
        var a=new SentenceAnalysis("Il travaille chaque soir, Paul.",tokens,2,null,List.of(),List.of(cc));
        assertEquals("Chaque soir, il travaille, Paul.",new SentenceTransformerEngine(empty).transform(a,config).transformed());
    }

    @Test void frontedComplementLowersMistaggedElidedSubject() {
        var analyzer = new FrenchNlpAnalyzer();
        var service = new SentenceTransformerService(analyzer, empty);
        var movementOnly = new TransformationConfig(false, true, false, false, false, true, true, false, 17);
        assertEquals("Dans mon carnet, j’écris une note.",
                service.transform("J’écris une note dans mon carnet.", movementOnly).transformed());
        assertEquals("Dans son carnet, Jules écrit une note.",
                service.transform("Jules écrit une note dans son carnet.", movementOnly).transformed());
    }
}
