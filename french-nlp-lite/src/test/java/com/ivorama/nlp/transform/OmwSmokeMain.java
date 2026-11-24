package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
public final class OmwSmokeMain {
 public static void main(String[] args) {
  var p=new OmwFrenchSynonymProvider();
  if(p.synonym(new TokenData(1,"x","PUNCT"),SynonymRegister.COURANT).isPresent()) throw new AssertionError("Punctuation");
  System.out.println("OMW load and punctuation test OK");
 }
}
