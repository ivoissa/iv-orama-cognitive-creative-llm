package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
public class TextOffsetsSmokeMain {
  public static void main(String[] args) {
    String text = "  Le chat dort.\n\nLe chien court !  ";
    int b1=text.indexOf("Le chat"), e1=b1+"Le chat dort.".length();
    int b2=text.indexOf("Le chien"), e2=b2+"Le chien court !".length();
    var a=new SentenceAnalysis(text.substring(b1,e1),List.of(new TokenData(1,"Le","DET"),new TokenData(2,"chat","NOUN"),new TokenData(3,"dort","VERB"),new TokenData(4,".","PUNCT")),3,null,List.of(),List.of(),b1,e1);
    var b=new SentenceAnalysis(text.substring(b2,e2),List.of(new TokenData(1,"Le","DET"),new TokenData(2,"chien","NOUN"),new TokenData(3,"court","VERB"),new TokenData(4,"!","PUNCT")),3,null,List.of(),List.of(),b2,e2);
    var config=new TransformationConfig(false,false,false,false,false,false,false,false,42L,false);
    var engine=new SentenceTransformerEngine((token,register)->Optional.empty());
    var result=SentenceTransformerService.transformAnalyses(text,List.of(a,b),config,engine);
    if (!result.transformed().equals(text) || result.sentences().size()!=2)
      throw new AssertionError(result.transformed().replace("\n","[NL]"));
    System.out.println("TEXT OFFSETS: 4/4 OK");
  }
}
