package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
public final class OmwRankingTraceMain {
 public static void main(String[] args) {
  OmwFrenchSynonymProvider p=new OmwFrenchSynonymProvider();
  String[][] examples={{"rivière","eau"},{"banque","rive"},{"avocat","juriste"},{"pierre","roche"},{"rapport","étude"},{"chat","matou"},{"banque","argent"},{"pierre","jardin"}};
  int produced=0;
  for(String[] x:examples) {
   TokenData t=new TokenData(1,x[0],"NOUN");
   SentenceAnalysis sentence=new SentenceAnalysis(x[0]+" "+x[1]+".",List.of(t,new TokenData(2,x[1],"NOUN"),new TokenData(3,".","PUNCT")),1,null,List.of(),List.of());
   var ranked=p.explain(t,sentence);
   var actual=p.synonym(t,SynonymRegister.COURANT,sentence);
   if(actual.isPresent()) produced++;
   System.out.printf("%s + %s => %s ; candidats=%d%n",x[0],x[1],actual.orElse("ABSTENTION"),ranked.size());
   for(int i=0;i<Math.min(3,ranked.size());i++) {
    var r=ranked.get(i);
    System.out.printf("  %s [%s] voisinage=%.4f lesk=%.4f spécificité=%.4f total=%.4f%n",r.word(),r.synset(),r.neighborhood(),r.leskBonus(),r.candidateSpecificity(),r.total());
   }
  }
  System.out.printf("COUVERTURE %d/%d%n",produced,examples.length);
 }
}
