package com.ivorama.nlp.transform;
import java.util.List;
public record LexicalAttempt(int tokenIndex,String word,String lemma,String pos,String status,String reason,List<String> candidates,String selected,List<Check> checks){
 public LexicalAttempt{candidates=List.copyOf(candidates);checks=List.copyOf(checks);}
 public record Check(String candidate,String status,String reason,double score,String sense,String evidence){}
}
