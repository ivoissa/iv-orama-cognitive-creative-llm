package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
public final class LeskScoreDiagnosticMain {
 public static void main(String[] args) {
  FrenchLeskDisambiguator lesk=new FrenchLeskDisambiguator();
  OmwFrenchSynonymProvider omw=new OmwFrenchSynonymProvider();
  String[][] cases={{"rivière","eau"},{"banque","argent"},{"banque","rive"},{"avocat","juriste"},{"pierre","roche"},{"rapport","étude"},{"chat","matou"},{"pluie","nuage"}};
  for(String[] c:cases) {
   TokenData token=new TokenData(1,c[0],"NOUN");
   SentenceAnalysis sentence=new SentenceAnalysis(c[0]+" "+c[1],List.of(token,new TokenData(2,c[1],"NOUN")),1,null,List.of(),List.of());
   var ranked=omw.explain(token,sentence);
   if(ranked.stream().anyMatch(r->!Double.isFinite(r.total()))) throw new AssertionError("Score non fini");
   System.out.printf(Locale.ROOT,"%-9s + %-9s | candidats=%-3d | meilleur=%-18s | score=%6.4f | voisinage=%5.3f | lesk=%5.3f%n",c[0],c[1],ranked.size(),ranked.isEmpty()?"aucun":ranked.get(0).word(),ranked.isEmpty()?0:ranked.get(0).total(),ranked.isEmpty()?0:ranked.get(0).neighborhood(),ranked.isEmpty()?0:ranked.get(0).leskBonus());
  }
 }
}
