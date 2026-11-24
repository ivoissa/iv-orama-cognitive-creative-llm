package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.TokenData;
public class WolfRealIntegrationMain {
 public static void main(String[] args) {
  var wolf=WolfXmlSynonymProvider.bundled();
  require(wolf.synsetCount()>100000,"WOLF incomplet");
  require(wolf.indexedSynsetCount()>20000,"synsets multi-mots insuffisants");
  require(wolf.indexedLemmaCount()>20000,"index insuffisant");
  require(wolf.synonym(new TokenData(1,"chance","NOUN"),SynonymRegister.COURANT).isPresent(),"chance introuvable");
  require(wolf.synonym(new TokenData(1,"maison","NOUN"),SynonymRegister.COURANT).isPresent(),"maison introuvable");
  require(wolf.synonym(new TokenData(1,"beau","ADJ"),SynonymRegister.COURANT).isPresent(),"beau introuvable");
  require(wolf.synonym(new TokenData(1,"vivre","VERB"),SynonymRegister.COURANT).isPresent(),"vivre introuvable");
  require(wolf.synonym(new TokenData(1,"transcendantale","ADJ"),SynonymRegister.COURANT).isEmpty(),"absence attendue");
  System.out.printf("WOLF REAL INTEGRATION: 8/8 OK; synsets=%d, synonymSynsets=%d, indexedLemmas=%d%n",wolf.synsetCount(),wolf.indexedSynsetCount(),wolf.indexedLemmaCount());
 }
 private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
