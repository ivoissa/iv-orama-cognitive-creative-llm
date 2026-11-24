package fr.ivorama.cognitive.generative;

import org.junit.jupiter.api.Test;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests of the Unicode matching semantics against the unoptimized regex. */
class OutputVerifier0651Test {
    private static final WikipediaResearch.Evidence NONE=WikipediaResearch.empty(WikipediaResearch.State.NOT_REQUESTED,"");
    private static final String[] PIECES={"A","a","É","é","e\u0301","b","1","Ⅻ","٢","💫","🤖","𐐷","中","_","-","."," ","\t","\n","\u200D","\u0302","\uD83D","\uDC0B","œ","ſ","İ","ı","\u0000","：","″","ß"};

    private static String randomText(Random random){
        int length=1+random.nextInt(10);
        var b=new StringBuilder();
        for(int i=0;i<length;i++)b.append(PIECES[random.nextInt(PIECES.length)]);
        return b.toString();
    }
    private static boolean originalContains(String text,String term){
        String normalizedText=Normalizer.normalize(text,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        String normalizedTerm=Normalizer.normalize(term,Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(normalizedTerm)+"(?![\\p{L}\\p{N}_])")
                .matcher(normalizedText).find();
    }
    @Test void unicodeLiteralChecksMatchTheOriginalRegex(){
        var random=new Random(20261021);
        int tested=0;
        for(int i=0;i<6000;i++){
            String answer=randomText(random)+randomText(random)+randomText(random)+randomText(random);
            String term=i%3==0?randomText(random):answer.substring(random.nextInt(answer.length()));
            if(term.isBlank()||term.length()>80)continue;
            var rules=new OutputVerifier.Rules(OptionalInt.empty(),List.of(term),List.of());
            boolean actual=OutputVerifier.check(answer,rules,NONE,false).violations().stream()
                    .anyMatch(v->v.startsWith("FORBIDDEN_TERM:"));
            assertEquals(originalContains(answer,term),actual,"Term / unicode: iteration="+i);
            tested++;
        }
        assertTrue(tested>5500);
    }
    @Test void threeDistinctAnswerTypesRetainExactRestrictions(){
        // This tests literal enforcement, not quality of the generated reasoning.
        var noResearch=NONE;
        var javaRules=new OutputVerifier.Rules(OptionalInt.empty(),List.of("synchronized"),List.of("record"));
        var javaReport=OutputVerifier.check("Utiliser un record Java pour cette donnée.",javaRules,noResearch,false);
        assertTrue(javaReport.passed());
        var mqRules=new OutputVerifier.Rules(OptionalInt.empty(),List.of("redémarrer"),List.of("idempotence"));
        var mqReport=OutputVerifier.check("Vérifier l'idempotence avant de redémarrer le consumer MQ.",mqRules,noResearch,false);
        assertEquals(1,mqReport.violations().size());
        assertTrue(mqReport.violations().getFirst().startsWith("FORBIDDEN_TERM:"));
        var logicRules=new OutputVerifier.Rules(OptionalInt.empty(),List.of("preuve"),List.of("prémisses"));
        var logicReport=OutputVerifier.check("Les prémisses ne fournissent pas une preuve.",logicRules,noResearch,false);
        assertEquals(1,logicReport.violations().size());
    }
    @Test void normalizationAndUnicodeWordBoundariesRemainStable(){
        assertTrue(OutputVerifier.check("été!",new OutputVerifier.Rules(OptionalInt.empty(),List.of(),List.of("e\u0301te\u0301")),NONE,false).passed());
        assertTrue(OutputVerifier.check("prémisses_suite",new OutputVerifier.Rules(OptionalInt.empty(),List.of("prémisses"),List.of()),NONE,false).passed());
        assertEquals(1,OutputVerifier.check("valeur. [W9]",new OutputVerifier.Rules(OptionalInt.empty(),List.of(),List.of()),NONE,false).violations().size());
    }
}
