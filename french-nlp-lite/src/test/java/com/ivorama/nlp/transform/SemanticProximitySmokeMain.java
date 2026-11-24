package com.ivorama.nlp.transform;
import java.util.List;
public final class SemanticProximitySmokeMain {
 public static void main(String[] args) {
  SemanticProximityScorer scorer=new SemanticProximityScorer(new OmwFrenchSynonymProvider());
  var ranked=scorer.rank("rivière",List.of("fleuve","pluie","rivière"),"NOUN");
  if(ranked.size()!=2) throw new AssertionError("nombre de candidats");
  for(var s:ranked) {
   if(s.proximity()<0||s.proximity()>1) throw new AssertionError("score invalide");
   if(s.directSynset()!=!s.sharedSynsets().isEmpty()) throw new AssertionError("preuve incohérente");
   System.out.println(s.candidate()+" = "+s.proximity()+" ("+s.evidence()+")");
  }
  if(scorer.score("rivière","rivière","NOUN").proximity()!=1.0) throw new AssertionError("identité");
  if(scorer.score("rivière","fleuve","UNKNOWN").proximity()!=0.0) throw new AssertionError("POS");
 }
}
