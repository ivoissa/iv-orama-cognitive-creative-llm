package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
/** Conservative safety probes; NOT a benchmark of sense accuracy. */
public final class FrenchLeskSmokeMain {
 static SentenceAnalysis sentence(String... words) {
  List<TokenData> tokens = new ArrayList<>();
  for (int i=0;i<words.length;i++) tokens.add(new TokenData(i+1,words[i],"NOUN"));
  return new SentenceAnalysis(String.join(" ",words),tokens,1,null,List.of(),List.of());
 }
 public static void main(String[] args) {
  OmwFrenchSynonymProvider provider=new OmwFrenchSynonymProvider();
  String[][] probes = {
   {"avocat","tribunal","client","juge"},
   {"avocat","salade","fruit","manger"},
   {"banc","parc","jardin","bois"},
   {"vol","avion","aéroport","voyage"},
   {"pièce","théâtre","acteur","spectacle"}
  };
  int abstentions=0;
  for(String[] words:probes) {
   var sentence=sentence(words);
   var result=provider.synonym(sentence.tokens().get(0),SynonymRegister.COURANT,sentence);
   System.out.println(String.join(" ",words)+" -> "+result.orElse("ABSTENTION"));
   if(result.isEmpty()) abstentions++;
  }
  if(abstentions!=5) throw new AssertionError("Expected 5 safe abstentions; got "+abstentions);
  System.out.println("5/5 conservative abstention checks passed. Sense-selection accuracy NOT established.");
 }
}
