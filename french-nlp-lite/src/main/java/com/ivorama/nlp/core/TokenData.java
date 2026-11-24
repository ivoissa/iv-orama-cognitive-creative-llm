package com.ivorama.nlp.core;

import java.util.Objects;

/** Token normalisé, indépendant de CoreNLP pour rendre le moteur testable sans modèle NLP. */
public record TokenData(int index, String word, String pos, int beginOffset, int endOffset,
                        String lemma, String gender, String number, int headIndex, String dependency) {
    public TokenData(int index,String word,String pos){this(index,word,pos,-1,-1,"","","",0,"");}
    public TokenData(int index,String word,String pos,int begin,int end){this(index,word,pos,begin,end,"","","",0,"");}
    public TokenData {
        if (index < 1) throw new IllegalArgumentException("index doit être >= 1");
        word = Objects.requireNonNull(word, "word");
        pos = Objects.requireNonNullElse(pos, "");
        lemma=Objects.requireNonNullElse(lemma,"");gender=Objects.requireNonNullElse(gender,"");
        number=Objects.requireNonNullElse(number,"");dependency=Objects.requireNonNullElse(dependency,"");
        if(beginOffset < -1 || endOffset < -1 || (beginOffset<0)!=(endOffset<0)||endOffset<beginOffset)throw new IllegalArgumentException("Offsets invalides");
    }

    public boolean isNoun() {
        return "NOUN".equals(pos) || "PROPN".equals(pos) || "NC".equals(pos) || "N".equals(pos)||"NPP".equals(pos);
    }

    public boolean isAdjective() {
        return "ADJ".equals(pos) || pos.startsWith("ADJ");
    }

    public boolean isPunctuation() {
        return "PUNCT".equals(pos) || word.matches("[.,;:!?…()\\[\\]{}]+") ;
    }
}
