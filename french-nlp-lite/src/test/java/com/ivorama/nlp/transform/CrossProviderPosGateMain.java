package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
import java.util.*;
/** Regression autonome: un fournisseur externe ne contourne pas le POS OMW. */
public final class CrossProviderPosGateMain {
 public static void main(String[] args) {
  var omw=new OmwFrenchSynonymProvider();
  var token=new TokenData(1,"pluie","NOUN");
  if(!omw.isIndexed("manger") || omw.hasPartOfSpeech("manger","NOUN"))
   throw new AssertionError("Fixture lexicale non adaptée");
  SynonymProvider rogue=new SynonymProvider() {
   public Optional<String> synonym(TokenData t,SynonymRegister r){return Optional.of("manger");}
   public boolean isCuratedCandidate(TokenData t,SynonymRegister r,String c){return true;}
  };
  var combined=new CombinedSynonymProvider(List.of(new CombinedSynonymProvider.Source("external",rogue)),omw);
  if(combined.candidates(token,SynonymRegister.COURANT,null).contains("manger"))
   throw new AssertionError("Nom -> verbe accepté");
  System.out.println("PASS: POS transversal pluie -> manger rejeté");
 }
}
