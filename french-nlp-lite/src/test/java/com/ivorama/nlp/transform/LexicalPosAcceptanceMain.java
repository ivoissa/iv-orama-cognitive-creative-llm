package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
/** Validation sans CoreNLP : ressources OMW/WOLF réelles et catégories POS explicites. */
public final class LexicalPosAcceptanceMain {
 public static void main(String[] args) {
  OmwFrenchSynonymProvider omw=new OmwFrenchSynonymProvider();
  WolfXmlSynonymProvider wolf=WolfXmlSynonymProvider.bundled();
  TokenData noun=new TokenData(1,"rivière","NOUN");
  SentenceAnalysis context=new SentenceAnalysis("La rivière coule.",List.of(noun,new TokenData(2,"coule","VERB")),1,null,List.of(),List.of());
  var nouns=omw.candidates(noun,SynonymRegister.COURANT,context);
  if(nouns.isEmpty()) throw new AssertionError("OMW ne retourne aucun nom");
  var verbs=omw.candidates(new TokenData(1,"rivière","VERB"),SynonymRegister.COURANT,context);
  if(!verbs.isEmpty()) throw new AssertionError("Le filtre POS OMW laisse passer un nom comme verbe: "+verbs);
  var wolfNouns=wolf.candidates(noun,SynonymRegister.COURANT,context);
  var wolfVerbs=wolf.candidates(new TokenData(1,"rivière","VERB"),SynonymRegister.COURANT,context);
  System.out.println("OMW rivière/NOUN: "+nouns);
  System.out.println("OMW rivière/VERB: "+verbs);
  System.out.println("WOLF rivière/NOUN: "+wolfNouns);
  System.out.println("WOLF rivière/VERB: "+wolfVerbs);
  System.out.println("WOLF synsets: "+wolf.synsetCount()+", lemmes indexes: "+wolf.indexedLemmaCount());
  System.out.println("POS acceptance: OK");
 }
}
