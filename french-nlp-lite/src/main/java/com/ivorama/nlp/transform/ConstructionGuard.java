package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
/** Conservative local guard for adjective + de complement: no unsupported new valency. */
final class ConstructionGuard {
 private ConstructionGuard(){}
 static boolean compatible(SentenceAnalysis a,TokenData token,String candidate,LexiconMorphologicalEngine morph){
  int i=a.tokens().indexOf(token);if(i<0||i+1>=a.tokens().size())return true;
  String next=LexiconMorphologicalEngine.norm(a.tokens().get(i+1).word());
  if(!Set.of("de","d'","du","des").contains(next))return true;
  // Attested past participles may retain a complement; not a full valency lexicon.
  String lemma=morph.lemma(candidate,"ADJ").orElse(candidate);
  if(morph.paradigm(lemma,"ADJ").stream().anyMatch(f->f.tags().contains("ppas")))return true;
  var evidence=RobertGroupEvidence.load();
  String original=morph.lemma(token.word(),"ADJ").orElse(token.word());
  var senses=new HashSet<>(evidence.sourceSenses(original,"a"));senses.retainAll(evidence.sourceSenses(candidate,"a"));
  return !senses.isEmpty();
 }
}
