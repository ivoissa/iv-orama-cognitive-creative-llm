package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
/** Tests du vrai index OMW; POS annotés manuellement (pas un test CoreNLP). */
public final class OmwNeighborhoodDiagnosticMain {
 public static void main(String[] args) {
  OmwFrenchSynonymProvider p=new OmwFrenchSynonymProvider();
  String[][] samples={
   {"rivière", "NOUN", "eau", "NOUN"},
   {"banque", "NOUN", "rive", "NOUN"},
   {"avocat", "NOUN", "juriste", "NOUN"},
   {"pierre", "NOUN", "roche", "NOUN"},
   {"rapport", "NOUN", "étude", "NOUN"},
   {"chat", "NOUN", "matou", "NOUN"},
   {"banque", "NOUN", "argent", "NOUN"},
   {"pierre", "NOUN", "jardin", "NOUN"}
  };
  int proposals=0;
  for (String[] s:samples) {
   TokenData target=new TokenData(1,s[0],s[1]);
   SentenceAnalysis context=new SentenceAnalysis(s[0]+" "+s[2]+".",List.of(target,new TokenData(2,s[2],s[3]),new TokenData(3,".","PUNCT")),1,null,List.of(),List.of());
   Optional<String> result=p.synonym(target,SynonymRegister.COURANT,context);
   if(result.isPresent()) proposals++;
   System.out.printf("%-10s | voisin=%-10s | sortie=%s%n",s[0],s[2],result.orElse("ABSTENTION"));
   if(result.isPresent() && result.get().equalsIgnoreCase(s[0]))throw new AssertionError("Identité");
  }
  System.out.printf("TOTAL CONTEXTE: %d/%d propositions%n",proposals,samples.length);
  if(proposals==0)throw new AssertionError("Aucune couverture");
 }
}
