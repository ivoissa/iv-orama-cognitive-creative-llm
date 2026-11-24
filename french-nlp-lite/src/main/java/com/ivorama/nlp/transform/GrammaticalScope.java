package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Closed grammatical operators are data, never corpus-specific noun replacement exceptions. */
final class GrammaticalScope {
 private static final Set<String> OPERATORS=load();
 private static final Set<String> QUOTES=Set.of("«","»","\"","“","”","``","''");
 private static final LexiconMorphologicalEngine MORPH=new LexiconMorphologicalEngine();
 private static Set<String> load(){var set=new HashSet<String>();try(var in=GrammaticalScope.class.getResourceAsStream("/grammatical-scope.tsv")){
  if(in==null)throw new IllegalStateException("Inventaire grammatical absent");
  try(var r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String s;while((s=r.readLine())!=null)if(!s.isBlank()&&!s.startsWith("#"))set.add(s.split("\t")[0]);}
 }catch(IOException e){throw new IllegalStateException(e);}return Set.copyOf(set);}
 static boolean operator(TokenData token){return !token.isNoun()&&(MORPH.negative(token.word())||OPERATORS.contains(LexiconMorphologicalEngine.norm(token.word())));}
 static boolean localOperator(SentenceAnalysis a,TokenData target){
  // Keep operators in the same punctuation-bounded clause; unrelated clauses do not veto emphasis.
  return a.tokens().stream().filter(t->operator(t)&&(MORPH.negative(t.word())||t.headIndex()==target.index())).anyMatch(t->{
   int lo=Math.min(t.index(),target.index()),hi=Math.max(t.index(),target.index());
   return a.tokens().stream().noneMatch(x->x.index()>lo&&x.index()<hi&&separator(x.word()));
  });
 }
 static boolean matrixOperator(SentenceAnalysis a,TokenData t){
  var seen=new HashSet<Integer>();
  while(t.index()!=a.rootTokenIndex()&&t.headIndex()>0&&seen.add(t.index())){
   if(t.dependency().startsWith("acl")||Set.of("advcl","ccomp","conj").contains(t.dependency())||t.isNoun())return false;
   t=a.token(t.headIndex());
  }return t.index()==a.rootTokenIndex();
 }
 static String blockingReason(SentenceAnalysis a){
  if(!Set.of("VERB","AUX","V","VINF","VPP","VPR","VS").contains(a.token(a.rootTokenIndex()).pos()))return "NO_MOVEMENT_NON_VERBAL_ROOT";
  int subjectStart=a.subject()==null?a.rootTokenIndex():Math.min(a.subject().start(),a.rootTokenIndex());
  // An initial subordinator attached to the matrix verb but outside every clause
  // constituent is evidence of unresolved scope, not a movable standalone modifier.
  if(a.tokens().stream().anyMatch(t->t.index()<subjectStart&&t.pos().equals("SCONJ")
      &&a.circumstantials().stream().noneMatch(c->c.contains(t.index()))))return "NO_MOVEMENT_UNRESOLVED_SUBORDINATOR_SCOPE";
  if(a.tokens().stream().anyMatch(t->t.word().equals("?")&&t.index()>=clauseStart(a)&&t.index()<=clauseEnd(a)+1))return "NO_MOVEMENT_INTERROGATIVE_SCOPE";
  if(a.tokens().stream().anyMatch(t->QUOTES.contains(t.word())))return "NO_MOVEMENT_QUOTATION_SCOPE";
  if(a.tokens().stream().anyMatch(t->operator(t)&&matrixOperator(a,t)))return "NO_MOVEMENT_NEGATION_OR_OPERATOR_SCOPE";
  return "";
 }
 static boolean structuralProtected(SentenceAnalysis a){return !blockingReason(a).isEmpty();}
 static boolean quoted(SentenceAnalysis a,TokenData target){
  var closers=new ArrayDeque<String>();boolean unmatched=false;
  for(var token:a.tokens()){
   String w=token.word();
   if(w.equals("«"))closers.push("»");else if(w.equals("“"))closers.push("”");else if(w.equals("``"))closers.push("''");
   else if(w.equals("\"")){if(!closers.isEmpty()&&closers.peek().equals(w))closers.pop();else closers.push(w);}
   else if(Set.of("»","”","''").contains(w)){if(!closers.isEmpty()&&closers.peek().equals(w))closers.pop();else unmatched=true;}
   if(token.index()==target.index())return unmatched||!closers.isEmpty();
  }
  return unmatched;
 }
 static int clauseStart(SentenceAnalysis a){return a.tokens().stream().filter(t->t.index()<a.rootTokenIndex()&&separator(t.word())).mapToInt(TokenData::index).max().orElse(0)+1;}
 static int clauseEnd(SentenceAnalysis a){return a.tokens().stream().filter(t->t.index()>a.rootTokenIndex()&&separator(t.word())).mapToInt(TokenData::index).min().orElse(a.tokens().get(a.tokens().size()-1).index()+1)-1;}
 static boolean separator(String s){return Set.of(";",":",".","!","?","…").contains(s);}
 static boolean withinRootClause(SentenceAnalysis a,Constituent c){return c.start()>=clauseStart(a)&&c.end()<=clauseEnd(a)&&a.tokens().stream().filter(t->c.contains(t.index())).noneMatch(t->separator(t.word())||QUOTES.contains(t.word()));}
 static boolean completeComplement(SentenceAnalysis a,Constituent c){
  var content=a.tokens().stream().filter(t->c.contains(t.index())).toList();
  if(content.stream().allMatch(t->Set.of("SCONJ","CCONJ","ADP","DET","PART","PUNCT").contains(t.pos())))return false;
  var before=a.tokens().stream().filter(t->t.index()==c.start()-1).findFirst();
  return before.isEmpty()||!Set.of("SCONJ","ADP").contains(before.get().pos());
 }
 static boolean alreadyFronted(SentenceAnalysis a,Constituent c){int start=a.subject()==null?a.rootTokenIndex():Math.min(a.rootTokenIndex(),a.subject().start());return c.start()<=a.firstLexicalIndex()||c.end()<start;}
}
