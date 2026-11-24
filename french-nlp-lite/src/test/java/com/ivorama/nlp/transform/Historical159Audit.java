package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import com.ivorama.nlp.evaluation.FunctionalEvaluation;import javax.json.*;import java.nio.file.*;import java.util.*;
/** Replays all original rejected pairs through real CoreNLP analyses and the common validator. */
public final class Historical159Audit {
 public static void main(String[] args)throws Exception{
  Path baseline=Path.of(args[0]),yaml=Path.of(args[1]),out=Path.of(args[2]);JsonObject old;try(var r=Json.createReader(Files.newBufferedReader(baseline))){old=r.readObject();}
  var original=old.getJsonObject("result");String text=original.getString("original");long init=System.nanoTime();var analyzer=new FrenchNlpAnalyzer();var analyses=analyzer.analyze(text);var provider=SynonymProviderFactory.create(yaml);var config=TransformationConfigLoader.load(yaml);double initMs=(System.nanoTime()-init)/1e6;
  var rows=new ArrayList<Object>();int sentenceIndex=0;
  for(var rawSentence:original.getJsonArray("sentences")){var sentence=rawSentence.asJsonObject();var analysis=analyses.get(sentenceIndex);int sourceSentence=sentenceIndex++;
   for(var rawAttempt:sentence.getJsonArray("lexicalAttempts")){var attempt=rawAttempt.asJsonObject();int targetIndex=attempt.getInt("tokenIndex");String source=attempt.getString("word");
    for(var rawCheck:attempt.getJsonArray("checks")){var check=rawCheck.asJsonObject();if(!check.getString("status").equals("REJECTED"))continue;String candidate=check.getString("candidate");
     SynonymProvider probe=new SynonymProvider(){
      private boolean target(TokenData t){return t.index()==targetIndex;}
      public Optional<String> synonym(TokenData t,SynonymRegister r){return target(t)?Optional.of(candidate):Optional.empty();}
      public List<String> candidates(TokenData t,SynonymRegister r,SentenceAnalysis a){return target(t)?List.of(candidate):List.of();}
      public List<String> inventory(TokenData t,SynonymRegister r,SentenceAnalysis a){return candidates(t,r,a);}
      public CandidateDecision assess(TokenData t,SynonymRegister r,String c,SentenceAnalysis a){return provider.assess(t,r,c,a);}
      public boolean isCuratedCandidate(TokenData t,SynonymRegister r,String c){return provider.isCuratedCandidate(t,r,c);}
      public boolean supportsRegisters(){return provider.supportsRegisters();}
     };
     boolean aligned=analysis.tokens().stream().anyMatch(t->t.index()==targetIndex&&t.word().equals(source));long started=System.nanoTime();var result=new SentenceTransformerEngine(probe).transform(analysis,config,false);double ms=(System.nanoTime()-started)/1e6;
     var actualInventory=new TreeSet<String>();for(var token:analysis.tokens())if(token.index()==targetIndex){actualInventory.addAll(provider.inventory(token,SynonymRegister.COURANT,analysis));if(!token.lemma().isBlank())actualInventory.addAll(provider.inventory(new TokenData(token.index(),token.lemma(),token.pos()),SynonymRegister.COURANT,analysis));}
     var row=new LinkedHashMap<String,Object>();row.put("id",rows.size()+1);row.put("sentence",sourceSentence+1);row.put("source",source);row.put("candidate",candidate);row.put("resourceCandidates",actualInventory);row.put("historicalRejection",check.getString("reason"));row.put("originalTokenAligned",aligned);row.put("realCoreNlpAnalysis",analysis);row.put("result",result);row.put("transformMilliseconds",ms);rows.add(row);
    }
   }
  }
  if(rows.size()!=159)throw new IllegalStateException("Expected 159 original rejected pairs, found "+rows.size());
  Files.createDirectories(out.toAbsolutePath().getParent());Files.writeString(out,FunctionalEvaluation.json(Map.of("initializationMs",initMs,"rows",rows,"java",System.getProperty("java.version"),"sourceAudit",baseline,"networkRequests",0,"note","Forced enumeration only; no semantic or grammatical veto bypassed; no new Wikipedia requests")).toString());System.out.println("Replayed "+rows.size()+" original rejected candidates");
 }
}
