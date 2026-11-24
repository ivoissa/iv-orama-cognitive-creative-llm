package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
public final class Stabilisation1411SmokeMain {
    static int count=0;
    static void eq(String expected,String actual) {count++;if(!expected.equals(actual))throw new AssertionError("expected="+expected+" actual="+actual);}
    static SentenceAnalysis s(String text,List<TokenData> t,int root) {return new SentenceAnalysis(text,t,root,null,List.of(),List.of());}
    static SynonymProvider p(String from,String to,boolean curated) {return new SynonymProvider() {
      public Optional<String> synonym(TokenData t,SynonymRegister r){return t.word().equalsIgnoreCase(from)?Optional.of(to):Optional.empty();}
      public boolean isCuratedCandidate(TokenData t,SynonymRegister r,String c){return curated && t.word().equalsIgnoreCase(from) && c.equals(to);}
    };}
    public static void main(String[] args) {
      var cfg=new TransformationConfig(false,true,false,false,false,false,false,false,42L);
      eq("Une équipe travaille.",new SentenceTransformerEngine(p("équipe","cabine",false)).transform(s("Une équipe travaille.",List.of(new TokenData(1,"Une","DET"),new TokenData(2,"équipe","NOUN"),new TokenData(3,"travaille","VERB"),new TokenData(4,".","PUNCT")),3),cfg).transformed());
      eq("La rivière coule.",new SentenceTransformerEngine(p("rivière","fleuve",true),new ConservativeMorphologicalEngine()).transform(s("La rivière coule.",List.of(new TokenData(1,"La","DET"),new TokenData(2,"rivière","NOUN"),new TokenData(3,"coule","VERB"),new TokenData(4,".","PUNCT")),3),cfg).transformed());
      eq("Hier soir, il observait.",new SentenceTransformerEngine(p("soir","après-midi",true)).transform(s("Hier soir, il observait.",List.of(new TokenData(1,"Hier","ADV"),new TokenData(2,"soir","NOUN"),new TokenData(3,",","PUNCT"),new TokenData(4,"il","PRON"),new TokenData(5,"observait","VERB"),new TokenData(6,".","PUNCT")),5),cfg).transformed());
      eq("Un rapport précis.",SentenceRenderer.render(List.of("Un","rapport","précis",";",".")));
      eq("La pâtée au chien le chat donne.",new SentenceTransformerEngine((t,r)->Optional.empty()).transform(TestFixtures.yodaSimple(),new TransformationConfig(false,true,false,true,false,false,false,false,42L)).transformed());
      System.out.println("STABILISATION 1.4.11: "+count+"/"+count+" tests OK");
    }
}
