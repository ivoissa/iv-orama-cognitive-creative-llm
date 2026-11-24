package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import java.util.*;
/** Minimal UTF-16 splices; nominal spans include the determiner to support elision and CoreNLP MWTs. */
final class TokenTextEditor {
 private record Range(int start,int end){}
 static String apply(SentenceAnalysis a,Map<Integer,String> words){
  var changed=a.tokens().stream().filter(t->!t.word().equals(words.get(t.index()))).toList();if(changed.isEmpty())return a.originalText();
  if(a.tokens().stream().anyMatch(t->t.beginOffset()<0))return SentenceRenderer.render(a.tokens().stream().map(t->words.get(t.index())).toList());
  var ranges=new ArrayList<Range>();
  for(var token:changed){int lo=token.beginOffset(),hi=token.endOffset(),at=a.tokens().indexOf(token),head=at;
   if(token.pos().equals("DET")){head=at+1;while(head<a.tokens().size()&&a.tokens().get(head).isAdjective())head++;}
   else if(token.isAdjective()){
    head=at-1;while(head>=0&&a.tokens().get(head).isAdjective())head--;
    if(head<0||!a.tokens().get(head).isNoun()){head=at+1;while(head<a.tokens().size()&&a.tokens().get(head).isAdjective())head++;}
   }
   if(head>=0&&head<a.tokens().size()&&a.tokens().get(head).isNoun()){
    int left=head;while(left>0&&a.tokens().get(left-1).isAdjective())left--;
    if(left>0&&a.tokens().get(left-1).pos().equals("DET"))left--;
    lo=Math.min(lo,a.tokens().get(left).beginOffset());hi=Math.max(hi,a.tokens().get(head).endOffset());
   }
   boolean expanded;do{expanded=false;for(var t:a.tokens())if(t.beginOffset()<hi&&t.endOffset()>lo){int l=Math.min(lo,t.beginOffset()),h=Math.max(hi,t.endOffset());expanded|=l!=lo||h!=hi;lo=l;hi=h;}}while(expanded);
   if(lo<0||hi>a.originalText().length())throw new IllegalArgumentException("Offsets lexicaux incohérents");ranges.add(new Range(lo,hi));
  }
  ranges.sort(Comparator.comparingInt(Range::start));var merged=new ArrayList<Range>();
  for(var r:ranges){if(!merged.isEmpty()&&r.start<merged.get(merged.size()-1).end){var old=merged.remove(merged.size()-1);merged.add(new Range(old.start,Math.max(old.end,r.end)));}else merged.add(r);}
  var edits = new ArrayList<TextEdit>();
  for(var r:merged){var group=a.tokens().stream().filter(t->t.beginOffset()<r.end&&t.endOffset()>r.start).toList();String after=SentenceRenderer.fragment(group.stream().map(t->SurfaceTokens.word(a,t,words)).toList(),false);after=LexiconMorphologicalEngine.caseLike(a.originalText().substring(r.start,r.end),after);edits.add(new TextEdit(r.start, r.end, after, 0, "lexical", Optional.empty()));}
  return new TextRewriter(List.of(sentence -> edits)).rewrite(a);
 }
}
