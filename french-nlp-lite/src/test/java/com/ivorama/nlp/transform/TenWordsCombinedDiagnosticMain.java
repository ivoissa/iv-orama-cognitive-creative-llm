package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
/** Corpus de diagnostic uniquement: aucune règle de production spécifique à ces mots. */
public final class TenWordsCombinedDiagnosticMain {
 public static void main(String[] args) {
  var omw=new OmwFrenchSynonymProvider();
  var combined=new CombinedSynonymProvider(List.of(
   new CombinedSynonymProvider.Source("OMW",omw),
   new CombinedSynonymProvider.Source("WOLF",WolfXmlSynonymProvider.bundled()),
   new CombinedSynonymProvider.Source("TSV",new ResourceSynonymProvider())),omw);
  for(String word:List.of("chat","chien","maison","livre","banque","avocat","pierre","rapport","pluie","rivière")) {
   var token=new TokenData(1,word,"NOUN");
   var context=new SentenceAnalysis(word,List.of(token),1,null,List.of(),List.of());
   var ranked=combined.explain(token,SynonymRegister.COURANT,context);
   System.out.printf("%-10s candidats=%-3d retenu=%-18s score=%.4f%n",word,ranked.size(),
    ranked.isEmpty()?"ABSTENTION":ranked.get(0).word(),ranked.isEmpty()?0:ranked.get(0).total());
  }
 }
}
