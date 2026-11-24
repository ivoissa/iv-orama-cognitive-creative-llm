package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Stabilisation1411Test {
    private static TransformationConfig config(boolean yoda) {
        return new TransformationConfig(false,true,false,yoda,false,false,false,false,42L);
    }
    private static SentenceAnalysis simple(String text, List<TokenData> tokens, int root) {
        return new SentenceAnalysis(text,tokens,root,null,List.of(),List.of());
    }
    private static SynonymProvider provider(String word, String value, boolean curated) {
        return new SynonymProvider() {
            public Optional<String> synonym(TokenData t, SynonymRegister r) {
                return t.word().equalsIgnoreCase(word) ? Optional.of(value) : Optional.empty();
            }
            public boolean isCuratedCandidate(TokenData t, SynonymRegister r, String c) {
                return curated && t.word().equalsIgnoreCase(word) && c.equals(value);
            }
        };
    }
    @Test void rejectsUnverifiedLexicalCandidate() {
        var a = simple("Une équipe travaille.", List.of(new TokenData(1,"Une","DET"),new TokenData(2,"équipe","NOUN"),new TokenData(3,"travaille","VERB"),new TokenData(4,".","PUNCT")),3);
        var r = new SentenceTransformerEngine(provider("équipe","cabine",false)).transform(a,config(false));
        assertEquals("Une équipe travaille.",r.transformed());
        assertTrue(r.replacements().isEmpty());
    }
    @Test void unknownGenderAbstainsInsteadOfGuessing() {
        var a = simple("La rivière coule.",List.of(new TokenData(1,"La","DET"),new TokenData(2,"rivière","NOUN"),new TokenData(3,"coule","VERB"),new TokenData(4,".","PUNCT")),3);
        // This invariant explicitly tests the no-evidence adapter. The default now has attested morphology.
        assertEquals("La rivière coule.",new SentenceTransformerEngine(provider("rivière","fleuve",true),new ConservativeMorphologicalEngine()).transform(a,config(false)).transformed());
    }
    @Test void temporalExpressionRemainsIntact() {
        var a = simple("Hier soir, il observait.",List.of(new TokenData(1,"Hier","ADV"),new TokenData(2,"soir","NOUN"),new TokenData(3,",","PUNCT"),new TokenData(4,"il","PRON"),new TokenData(5,"observait","VERB"),new TokenData(6,".","PUNCT")),5);
        assertEquals("Hier soir, il observait.",new SentenceTransformerEngine(provider("soir","après-midi",true)).transform(a,config(false)).transformed());
    }
    @Test void terminalPunctuationNeverDuplicatesSemicolon() {
        assertEquals("Un rapport précis.",SentenceRenderer.render(List.of("Un","rapport","précis",";",".")));
    }
    @Test void yodaStillMovesBothObjects() {
        var r = new SentenceTransformerEngine((t,reg)->Optional.empty()).transform(TestFixtures.yodaSimple(),config(true));
        assertEquals("La pâtée au chien le chat donne.",r.transformed());
    }
}
