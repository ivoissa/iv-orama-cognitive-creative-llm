package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
/** Bounded lexical taxonomy evidence, not a general semantic parser. */
final class FunctionalCompatibility {
 private FunctionalCompatibility(){}
 static String reason(SentenceAnalysis a,TokenData t,String original,String candidate,String sense,RobertGroupEvidence e,LexiconMorphologicalEngine m){
  String pos=t.isNoun()?"n":"a";
  original=m.lemma(original,t.pos()).orElse(original);
  var from=sense.isEmpty()?e.sourceSenses(original,pos):Set.of(sense);var to=e.sourceSenses(candidate,pos);
  if(t.isNoun()){
   if((m.attestedAnimateNoun(original)||e.attestedAnimate(original))&&!m.possibleAnimateNoun(candidate)&&!e.possibleAnimate(candidate))return "FUNCTIONAL_ANIMATE_CONFLICT";
   if(!from.isEmpty()&&!to.isEmpty()){
    var roots=new HashSet<String>();for(String x:from)roots.addAll(e.cachedAncestors(x,3));
    boolean related=to.stream().anyMatch(x->e.cachedAncestors(x,3).stream().anyMatch(roots::contains));
    boolean spatial=a.tokens().stream().anyMatch(x->(x.headIndex()==t.index()&&x.dependency().startsWith("case")||x.index()==t.index()-2)&&Set.of("dans","sur","sous","derrière","devant","depuis").contains(x.word().toLowerCase(Locale.ROOT)));
    if(spatial){var physical="00001930-n";boolean sourcePhysical=from.stream().anyMatch(x->e.cachedAncestors(x,20).contains(physical));boolean targetPhysical=to.stream().anyMatch(x->e.cachedAncestors(x,20).contains(physical));if(sourcePhysical&&!targetPhysical)return "SPATIAL_PHYSICAL_CONFLICT";}
    if(!related)return "NOUN_TAXONOMY_UNSUPPORTED";
    boolean measure=a.tokens().stream().anyMatch(x->x.pos().equals("NUM")||x.pos().equals("Z")||x.pos().equals("CD")||x.dependency().startsWith("nummod"));
    if(measure&&t.dependency().startsWith("nsubj")&&Collections.disjoint(from,to)&&from.stream().noneMatch(x->e.explicitNearSynonym(x,candidate,pos)))return "MEASURE_SUBJECT_SENSE_UNPROVEN";
   }
  }else if(t.isAdjective()){
   boolean beforeNoun=a.tokens().stream().anyMatch(x->x.index()==t.index()+1&&x.isNoun());
   if(beforeNoun&&!m.readings(candidate,"NOUN").isEmpty()&&to.isEmpty())return "PRENOMINAL_CATEGORY_UNPROVEN";
   if(!from.isEmpty()&&to.isEmpty()&&!m.paradigm(candidate,"ADJ").stream().anyMatch(f->f.tags().contains("ppas")))return "ADJECTIVE_SENSE_MISSING";
   if(!from.isEmpty()&&!to.isEmpty()&&Collections.disjoint(from,to)&&from.stream().noneMatch(x->e.explicitNearSynonym(x,candidate,pos)||e.neighbors(x).stream().anyMatch(to::contains)))return "ADJECTIVE_RELATION_UNSUPPORTED";
  }
  return "";
 }
}
