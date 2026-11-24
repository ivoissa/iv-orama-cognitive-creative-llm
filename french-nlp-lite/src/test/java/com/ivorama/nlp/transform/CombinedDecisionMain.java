package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
public class CombinedDecisionMain {
 public static void main(String[] args) {
  TokenData t=new TokenData(1,"rivière","NOUN");
  SentenceAnalysis ctx=new SentenceAnalysis("rivière eau",List.of(t,new TokenData(2,"eau","NOUN")),1,null,List.of(),List.of());
  OmwFrenchSynonymProvider omw=new OmwFrenchSynonymProvider();
  SynonymProvider curated=new SynonymProvider(){
   public Optional<String> synonym(TokenData x,SynonymRegister r){return Optional.of("fleuve");}
   public boolean isCuratedCandidate(TokenData x,SynonymRegister r,String c){return true;}
  };
  CombinedSynonymProvider combined=new CombinedSynonymProvider(List.of(new CombinedSynonymProvider.Source("OMW",omw),new CombinedSynonymProvider.Source("TSV",curated)),omw);
  var ranking=combined.explain(t,SynonymRegister.COURANT,ctx);
  if(ranking.isEmpty() || !ranking.get(0).word().equals("fleuve") || ranking.get(0).sources().size()!=2) throw new AssertionError(ranking);
  System.out.println("DECISION REELLE: "+ranking);
 }
}
