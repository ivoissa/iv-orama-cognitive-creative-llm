package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import java.util.*;
/** Original elisions are evidence: preserve them unless the following sound-bearing token changed. */
final class SurfaceTokens {
 static String word(SentenceAnalysis a,TokenData token,Map<Integer,String> words){
  String current=words.get(token.index());if(token.beginOffset()<0||token.endOffset()>a.originalText().length()||!current.equalsIgnoreCase(token.word()))return current;
  String surface=a.originalText().substring(token.beginOffset(),token.endOffset());
  if(!surface.endsWith("'")&&!surface.endsWith("’"))return current;
  int at=a.tokens().indexOf(token);if(at+1>=a.tokens().size())return current;var next=a.tokens().get(at+1);String after=words.get(next.index());
  boolean same=next.word().equalsIgnoreCase(after)||NominalAgreement.vowel(next.word())&&NominalAgreement.vowel(after);
  if(!same)return current;
  if(!current.isEmpty()&&Character.isLowerCase(current.codePointAt(0)))return Character.toLowerCase(surface.charAt(0))+surface.substring(1);
  return surface;
 }
}
