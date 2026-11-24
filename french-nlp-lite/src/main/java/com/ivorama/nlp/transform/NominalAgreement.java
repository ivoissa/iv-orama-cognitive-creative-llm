package com.ivorama.nlp.transform;

import com.ivorama.nlp.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Bounded agreement proven by a form dictionary. Unknown/ambiguous morphology => abstention. */
final class NominalAgreement {
 record Plan(Map<Integer,String> words,String evidence){}
 record Evaluation(Plan plan,String reason){}
 private static Evaluation deny(String reason){return new Evaluation(null,reason);}
 private static Evaluation accept(Plan plan){return new Evaluation(plan, "ATTESTED_AGREEMENT");}
 private record Determiner(String family,String masculine,String feminine,String plural,String vowelMasculine,String vowelFeminine){
  boolean has(String word){return List.of(masculine,feminine,plural,vowelMasculine,vowelFeminine).contains(word);}
  String form(String gender,String number,boolean vowel){return number.equals("pl")?plural:gender.equals("fem")?(vowel?vowelFeminine:feminine):(vowel?vowelMasculine:masculine);}
 }
 private static final List<Determiner> DETERMINERS=loadDeterminers();
 private final MorphologicalEngine morphology;
 private final LexiconMorphologicalEngine lexicon;
 NominalAgreement(MorphologicalEngine morphology){this.morphology=morphology;lexicon=morphology instanceof LexiconMorphologicalEngine l?l:null;}
 private static List<Determiner> loadDeterminers(){var out=new ArrayList<Determiner>();try(var in=NominalAgreement.class.getResourceAsStream("/determiner-paradigms.tsv")){
  if(in==null)throw new IllegalStateException("Paradigmes grammaticaux absents");
  try(var r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)if(!line.isBlank()&&!line.startsWith("#")){var a=line.split("\t",-1);if(a.length!=6)throw new IllegalStateException("Paradigme invalide");out.add(new Determiner(a[0],a[1],a[2],a[3],a[4],a[5]));}}
 }catch(IOException e){throw new IllegalStateException(e);}return List.copyOf(out);}
 static boolean vowel(String s){return s.matches("(?iu)^[aàâäeéèêëiîïoôöuùûüœæ].*");}
 private static boolean uncertainInitial(String s){return s.matches("(?iu)^[hy].*");}
 private Optional<MorphologicalFeatures> resolve(TokenData token,TokenData determiner){
  var basic=morphology.analyze(token);if(lexicon==null)return basic.hasAgreementEvidence()?Optional.of(basic):Optional.empty();
  String gender="",number="";
  if(determiner!=null){var d=lexicon.analyze(new TokenData(determiner.index(),determiner.word(),"DET"));gender=d.gender();number=d.number();
   String surface=LexiconMorphologicalEngine.norm(determiner.word());
   if(surface.endsWith("'")||vowel(token.word())&&DETERMINERS.stream().anyMatch(p->p.family.startsWith("possessive")&&p.vowelFeminine.equals(surface)))gender="";
  }
  var unique=new LinkedHashSet<MorphologicalFeatures>();
  for(var f:lexicon.readings(token.word(),token.pos()))for(String g:List.of("mas","fem"))for(String n:List.of("sg","pl"))
   if(f.gender(g)&&f.number(n)&&(gender.isEmpty()||gender.equals(g))&&(number.isEmpty()||number.equals(n)))unique.add(new MorphologicalFeatures(f.lemma(),LexiconMorphologicalEngine.pos(token.pos()),g,n));
  return unique.size()==1?Optional.of(unique.iterator().next()):Optional.empty();
 }
 private Optional<String> targetForm(String candidate,String pos,String gender,String number){
  String lemma=lexicon==null?candidate:lexicon.lemma(candidate,pos).orElse(candidate);
  return morphology.inflect(lemma,new MorphologicalFeatures(lemma,pos,gender,number));
 }
 Optional<Plan> plan(SentenceAnalysis a,TokenData token,String candidate,Map<Integer,String> current,boolean allowGenderChange){return Optional.ofNullable(evaluate(a,token,candidate,current,allowGenderChange).plan());}
 Evaluation evaluate(SentenceAnalysis a,TokenData token,String candidate,Map<Integer,String> current,boolean allowGenderChange){
  if(lexicon==null)return deny("NO_ATTESTED_MORPHOLOGY");
  if(token.isAdjective()&&!ConstructionGuard.compatible(a,token,candidate,lexicon))return deny("ADJECTIVE_COMPLEMENT_UNPROVEN");
  if(token.isAdjective())return adjective(a,token,candidate,current);
  if(token.isNoun())return noun(a,token,candidate,current,allowGenderChange);
  return deny("UNSUPPORTED_SOURCE_CATEGORY");
 }
 private Evaluation adjective(SentenceAnalysis a,TokenData token,String candidate,Map<Integer,String> current){
  int at=a.tokens().indexOf(token);TokenData head=token.dependency().startsWith("amod")?a.tokens().stream().filter(t->t.index()==token.headIndex()&&t.isNoun()).findFirst().orElse(null):null;
  int left=at-1;while(left>=0&&a.tokens().get(left).isAdjective())left--;if(head==null&&left>=0&&a.tokens().get(left).isNoun())head=a.tokens().get(left);
  int right=at+1;while(head==null&&right<a.tokens().size()&&a.tokens().get(right).isAdjective())right++;if(head==null&&right<a.tokens().size()&&a.tokens().get(right).isNoun())head=a.tokens().get(right);
  if(head==null&&token.index()==a.rootTokenIndex()&&a.subject()!=null)head=a.tokens().stream().filter(t->a.subject().contains(t.index())&&t.isNoun()).findFirst().orElse(null);
  // Explicit original agreement overrides a parser attachment incompatible with the surface.
  var original=resolve(token,null);
  boolean headChanged=head!=null&&!Objects.equals(current.get(head.index()),head.word());
  if(head!=null&&!headChanged&&original.isEmpty()){
   // Epicene adjective after a genitive: attachment may be ambiguous; avoid inventing gender.
   final int hi=head.index();
   if(a.tokens().stream().anyMatch(t->t.index()==hi&&t.dependency().startsWith("nmod")))return deny("AMBIGUOUS_GENITIVE_ADJECTIVE_HEAD");
  }
  if(head!=null&&!headChanged&&original.isPresent()){
   var h=resolve(head,null);var o=original.get();
   if(h.isPresent()&&(!h.get().gender().equals(o.gender())||!h.get().number().equals(o.number())))head=null;
  }
  Optional<MorphologicalFeatures> features;
  if(head!=null){
   final int headIndex=head.index();
   var det=a.dependentsOf(headIndex).stream().filter(t->t.dependency().equals("det")).findFirst().orElse(null);
   TokenData currentDeterminer = det == null ? null
       : new TokenData(det.index(), current.get(det.index()), det.pos());
   features=resolve(new TokenData(head.index(),current.get(head.index()),head.pos()),currentDeterminer);
  }
  else features=resolve(new TokenData(token.index(),current.get(token.index()),token.pos()),null);
  if(features.isEmpty())return deny("SOURCE_ADJECTIVE_AGREEMENT_AMBIGUOUS");var f=features.get();var form=targetForm(candidate,"ADJ",f.gender(),f.number());if(form.isEmpty())return deny("TARGET_ADJECTIVE_FORM_NOT_ATTESTED");if(uncertainInitial(form.get()))return deny("ELISION_H_Y_UNCERTAIN");
  var changes=new TreeMap<Integer,String>();changes.put(token.index(),LexiconMorphologicalEngine.caseLike(current.get(token.index()),form.get()));
  if(head!=null){int first=a.tokens().indexOf(head);while(first>0&&a.tokens().get(first-1).isAdjective())first--;
   if(first>0&&a.tokens().get(first-1).pos().equals("DET")){
    var det=a.tokens().get(first-1);String d=LexiconMorphologicalEngine.norm(current.get(det.index()));var family=DETERMINERS.stream().filter(p->p.has(d)).toList();
    String next=changes.getOrDefault(a.tokens().get(first).index(),current.get(a.tokens().get(first).index()));
    if(family.size()==1&&changes.containsKey(a.tokens().get(first).index())&&!uncertainInitial(next))changes.put(det.index(),LexiconMorphologicalEngine.caseLike(current.get(det.index()),family.get(0).form(f.gender(),f.number(),vowel(next))));
   }
  }
  return accept(new Plan(Collections.unmodifiableMap(changes),"Dicollecte ADJ "+f.gender()+" "+f.number()));
 }
 private static boolean verifiedCopularPredicate(SentenceAnalysis a,TokenData head,TokenData adjective){
  if(adjective.index()!=a.rootTokenIndex()||!adjective.isAdjective()||a.subject()==null||!a.subject().contains(head.index()))return false;
  int low=Math.min(head.index(),adjective.index()),high=Math.max(head.index(),adjective.index());
  return a.tokens().stream().anyMatch(t->t.index()>low&&t.index()<high&&t.pos().equals("AUX"));
 }
 private Evaluation noun(SentenceAnalysis a,TokenData token,String candidate,Map<Integer,String> current,boolean allowGenderChange){
  if(Set.of("PROPN","NPP").contains(token.pos()))return deny("PROPER_NOUN_PRESERVED");
  int at=a.tokens().indexOf(token),left=at,right=at;
  while(left>0&&a.tokens().get(left-1).isAdjective())left--;
  while(right+1<a.tokens().size()&&a.tokens().get(right+1).isAdjective())right++;
  TokenData det=left>0&&a.tokens().get(left-1).pos().equals("DET")?a.tokens().get(left-1):null;
  if(!token.dependency().isBlank())det=a.dependentsOf(token.index()).stream().filter(t->t.dependency().equals("det")).findFirst().orElse(det);
  var dependentAdjectives=new LinkedHashSet<TokenData>();
  if(!token.dependency().isBlank()){
   dependentAdjectives.addAll(a.dependentsWithRelationPrefix(token.index(),"amod"));
   boolean expanded;do{expanded=false;for(var t:a.tokens())if(t.dependency().startsWith("conj")&&dependentAdjectives.stream().anyMatch(h->h.index()==t.headIndex())&&dependentAdjectives.add(t))expanded=true;}while(expanded);
  }else for(int j=left;j<=right;j++)if(j!=at)dependentAdjectives.add(a.tokens().get(j));
  TokenData surfaceDet=det;
  if(det!=null&&det.beginOffset()>=0&&det.endOffset()<=a.originalText().length())surfaceDet=new TokenData(det.index(),a.originalText().substring(det.beginOffset(),det.endOffset()),"DET");
  var source=resolve(token,surfaceDet);if(source.isEmpty())return deny("SOURCE_NOUN_FEATURES_AMBIGUOUS");var s=source.get();
  String targetLemma=lexicon.lemma(candidate,"NOUN").orElse(candidate);
  var available=lexicon.paradigm(targetLemma,"NOUN").stream().filter(f->f.number(s.number())).toList();
  var genders=new LinkedHashSet<String>();for(String g:List.of("mas","fem"))if(available.stream().anyMatch(f->f.gender(g)))genders.add(g);
  String gender=genders.contains(s.gender())?s.gender():genders.size()==1?genders.iterator().next():"";if(gender.isEmpty())return deny("TARGET_NOUN_GENDER_AMBIGUOUS_OR_UNATTESTED");
  boolean change=!gender.equals(s.gender());if(change&&!allowGenderChange)return deny("GENDER_CHANGE_REQUIRES_CROSS_SENTENCE_COREFERENCE");if(change&&det==null)return deny("GENDER_CHANGE_WITHOUT_DETERMINER");
  if(change&&a.subject()!=null&&a.subject().contains(token.index())&&a.tokens().stream().filter(t->a.subject().contains(t.index())&&t.isNoun()).count()>1)return deny("GENDER_CHANGE_COORDINATED_OR_COMPLEX_SUBJECT");
  // A changed gender may affect pronouns outside this group; no unproved coreference resolution.
  if(change&&a.tokens().stream().anyMatch(t->t.pos().equals("PRON")))return deny("GENDER_CHANGE_WITH_UNRESOLVED_PRONOUN");
  if(change){
   final int first=left,last=right;
   for(int j=0;j<a.tokens().size();j++)if(j<first||j>last){var other=a.tokens().get(j);
    boolean verifiedPredicate=verifiedCopularPredicate(a,token,other);
    if(other.isAdjective()&&!verifiedPredicate&&!dependentAdjectives.contains(other))return deny("GENDER_CHANGE_WITH_UNRESOLVED_ADJECTIVE_SCOPE");
    if(other.pos().startsWith("V")&&lexicon.readings(other.word(),"ADJ").stream().anyMatch(f->f.tags().contains("ppas")))return deny("GENDER_CHANGE_WITH_UNRESOLVED_PARTICIPLE");
   }
  }
  var form=targetForm(candidate,"NOUN",gender,s.number());if(form.isEmpty())return deny("TARGET_NOUN_FORM_NOT_ATTESTED");if(uncertainInitial(form.get()))return deny("ELISION_H_Y_UNCERTAIN");
  var edits=new LinkedHashMap<Integer,String>();edits.put(token.index(),LexiconMorphologicalEngine.caseLike(current.get(token.index()),form.get()));
  for(var adj:dependentAdjectives){if(!adj.isAdjective())return deny("DEPENDENT_ADJECTIVE_CATEGORY_CONFLICT");var lemma=lexicon.lemma(current.get(adj.index()),"ADJ");if(lemma.isEmpty())return deny("DEPENDENT_ADJECTIVE_LEMMA_AMBIGUOUS");var inflected=morphology.inflect(lemma.get(),new MorphologicalFeatures(lemma.get(),"ADJ",gender,s.number()));if(inflected.isEmpty())return deny("DEPENDENT_ADJECTIVE_FORM_NOT_ATTESTED");edits.put(adj.index(),LexiconMorphologicalEngine.caseLike(current.get(adj.index()),inflected.get()));}
  // UD copular clauses have an ADJ root; agreement with the subject is local and explicit.
  if(change&&verifiedCopularPredicate(a,token,a.token(a.rootTokenIndex()))){
   var predicate=a.token(a.rootTokenIndex());var f=targetForm(predicate.word(),"ADJ",gender,s.number());if(f.isEmpty())return deny("PREDICATE_ADJECTIVE_FORM_NOT_ATTESTED");edits.put(predicate.index(),LexiconMorphologicalEngine.caseLike(current.get(predicate.index()),f.get()));
  }
  if(det!=null){String d=LexiconMorphologicalEngine.norm(current.get(det.index()));var families=DETERMINERS.stream().filter(p->p.has(d)).toList();if(families.size()!=1){if(change)return deny("DETERMINER_PARADIGM_UNKNOWN");}
   else {String next=edits.getOrDefault(a.tokens().get(left).index(),current.get(a.tokens().get(left).index()));if(uncertainInitial(next))return deny("ELISION_H_Y_UNCERTAIN");String article=families.get(0).form(gender,s.number(),vowel(next));edits.put(det.index(),LexiconMorphologicalEngine.caseLike(current.get(det.index()),article));}
  }
  return accept(new Plan(Collections.unmodifiableMap(new TreeMap<>(edits)),"Dicollecte NOUN "+s.gender()+"→"+gender+" "+s.number()+"; DET/ADJ attested; local agreement only"));
 }
}
