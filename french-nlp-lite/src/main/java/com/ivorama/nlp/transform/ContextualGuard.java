package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
/** Guards newly introduced collisions and nominal modifiers, not original repetitions. */
final class ContextualGuard {
 private ContextualGuard(){}
 static boolean nominalModifier(SentenceAnalysis a,Constituent c){
  return a.tokens().stream().filter(t->c.contains(t.index())&& !c.contains(t.headIndex())&&t.headIndex()>0).anyMatch(t->t.dependency().startsWith("nmod")&&a.token(t.headIndex()).isNoun());
 }
 static boolean ambiguousCircumstantial(SentenceAnalysis sentence, Constituent group) {
  if (nominalModifier(sentence, group)) return true;
  var first = sentence.token(group.start());
  var previous = sentence.findToken(group.start() - 1);
  // A genitive immediately following a nominal head may be attached to the verb by the parser.
  if (first.pos().equals("ADP") && Set.of("de", "d'", "du", "des").contains(LexiconMorphologicalEngine.norm(first.word()))
      && previous.filter(TokenData::isNoun).isPresent()) return true;
  // An initial apparent oblique with nominal apposition can be the missing subject.
  return sentence.subject() == null && group.end() < sentence.rootTokenIndex()
      && sentence.tokens().stream().filter(t -> group.contains(t.index()))
          .anyMatch(t -> t.isNoun() && (t.dependency().equals("appos") || t.dependency().startsWith("appos:")));
 }
 static boolean introducesDuplicate(SentenceAnalysis a,TokenData target,String candidate,Map<Integer,String> words,MorphologicalEngine morph){
  String key=lemma(morph,target,candidate);String original=lemma(morph,target,target.word());if(key.equals(original))return false;
  for(var t:a.tokens())if(t.index()!=target.index()&&!t.isPunctuation()&&(t.isNoun()||t.isAdjective()||t.pos().startsWith("V"))){
   String current=words.get(t.index());if(current==null)continue;
   if(key.equals(LexiconMorphologicalEngine.norm(current))||key.equals(lemma(morph,t,current)))return true;
  }return false;
 }
 private static String lemma(MorphologicalEngine m,TokenData t,String s){String l=m.analyze(new TokenData(t.index(),s,t.pos())).lemma();return LexiconMorphologicalEngine.norm(l.isBlank()?s:l);}
}
