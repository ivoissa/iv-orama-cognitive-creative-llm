package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.Constituent;
import com.ivorama.nlp.core.ConstituentRole;
import com.ivorama.nlp.core.SentenceAnalysis;
import com.ivorama.nlp.core.TokenData;

import java.util.Arrays;
import java.util.List;

final class TestFixtures {
    private TestFixtures() {}

    static TokenData t(int i, String w, String pos) {
        return new TokenData(i, w, pos);
    }

    static Constituent c(ConstituentRole role, String rel, int... indices) {
        return new Constituent(role, Arrays.stream(indices).boxed().toList(), rel);
    }

    static SentenceAnalysis richSentence() {
        return new SentenceAnalysis(
                "Le petit chat noir curieux affamé apporte la pâtée au chien rapidement, silencieusement, dans le jardin.",
                List.of(
                        t(1,"Le","DET"), t(2,"petit","ADJ"), t(3,"chat","NOUN"), t(4,"noir","ADJ"),
                        t(5,"curieux","ADJ"), t(6,"affamé","ADJ"), t(7,"apporte","VERB"),
                        t(8,"la","DET"), t(9,"pâtée","NOUN"), t(10,"à","ADP"), t(11,"le","DET"),
                        t(12,"chien","NOUN"), t(13,"rapidement","ADV"), t(14,",","PUNCT"),
                        t(15,"silencieusement","ADV"), t(16,",","PUNCT"), t(17,"dans","ADP"),
                        t(18,"le","DET"), t(19,"jardin","NOUN"), t(20,".","PUNCT")),
                7,
                c(ConstituentRole.SUBJECT,"nsubj",1,2,3,4,5,6),
                List.of(c(ConstituentRole.DIRECT_OBJECT,"obj",8,9),
                        c(ConstituentRole.INDIRECT_OBJECT,"iobj",10,11,12)),
                List.of(c(ConstituentRole.CIRCUMSTANTIAL,"advmod",13),
                        c(ConstituentRole.CIRCUMSTANTIAL,"advmod",15),
                        c(ConstituentRole.CIRCUMSTANTIAL,"obl",17,18,19)));
    }

    static SentenceAnalysis threeAdjectives() {
        return new SentenceAnalysis(
                "Le petit chat noir curieux voit la souris.",
                List.of(t(1,"Le","DET"), t(2,"petit","ADJ"), t(3,"chat","NOUN"),
                        t(4,"noir","ADJ"), t(5,"curieux","ADJ"), t(6,"voit","VERB"),
                        t(7,"la","DET"), t(8,"souris","NOUN"), t(9,".","PUNCT")),
                6,
                c(ConstituentRole.SUBJECT,"nsubj",1,2,3,4,5),
                List.of(c(ConstituentRole.DIRECT_OBJECT,"obj",7,8)), List.of());
    }

    static SentenceAnalysis yodaSimple() {
        return new SentenceAnalysis(
                "Le chat donne la pâtée au chien.",
                List.of(t(1,"Le","DET"), t(2,"chat","NOUN"), t(3,"donne","VERB"),
                        t(4,"la","DET"), t(5,"pâtée","NOUN"), t(6,"à","ADP"),
                        t(7,"le","DET"), t(8,"chien","NOUN"), t(9,".","PUNCT")),
                3,
                c(ConstituentRole.SUBJECT,"nsubj",1,2),
                List.of(c(ConstituentRole.DIRECT_OBJECT,"obj",4,5),
                        c(ConstituentRole.INDIRECT_OBJECT,"iobj",6,7,8)), List.of());
    }

    static SentenceAnalysis circumstancesNoCommas() {
        return new SentenceAnalysis(
                "Le chat mange rapidement sa pâtée silencieusement dans le jardin.",
                List.of(t(1,"Le","DET"), t(2,"chat","NOUN"), t(3,"mange","VERB"),
                        t(4,"rapidement","ADV"), t(5,"sa","DET"), t(6,"pâtée","NOUN"),
                        t(7,"silencieusement","ADV"), t(8,"dans","ADP"), t(9,"le","DET"),
                        t(10,"jardin","NOUN"), t(11,".","PUNCT")),
                3,
                c(ConstituentRole.SUBJECT,"nsubj",1,2),
                List.of(c(ConstituentRole.DIRECT_OBJECT,"obj",5,6)),
                List.of(c(ConstituentRole.CIRCUMSTANTIAL,"advmod",4),
                        c(ConstituentRole.CIRCUMSTANTIAL,"advmod",7),
                        c(ConstituentRole.CIRCUMSTANTIAL,"obl",8,9,10)));
    }
}
