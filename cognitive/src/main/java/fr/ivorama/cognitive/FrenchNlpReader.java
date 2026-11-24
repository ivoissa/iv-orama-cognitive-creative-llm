package fr.ivorama.cognitive;

import java.io.*;
import java.util.*;
import opennlp.tools.sentdetect.*;
import opennlp.tools.tokenize.*;
import opennlp.tools.postag.*;
import opennlp.tools.lemmatizer.*;

/** Local, statistical annotations. Neither syntactic roles nor facts are certified here. */
public final class FrenchNlpReader {
 public static final String VERSION="OpenNLP-2.5.12/fr-UD-GSD-1.3.0";
 public record Token(int start,int end,String surface,String pos,String lemma,double score) {}
 public record Sentence(int start,int end,List<Token> tokens) {
  public Sentence {tokens=List.copyOf(tokens);}
 }
 public static final class Unavailable extends RuntimeException {
  public Unavailable(String message,Exception cause){super(message,cause);}
 }
 public static final class Limit extends IllegalArgumentException {public Limit(String message){super(message);}}
 private record Models(SentenceModel sentences,TokenizerModel tokens,POSModel pos,LemmatizerModel lemmas) {}
 private static volatile Models models;
 private static Models models(){
  var m=models;if(m!=null)return m;
  synchronized(FrenchNlpReader.class){
   if(models==null)try(
    var s=resource("sentence");var t=resource("tokens");var p=resource("pos");var l=resource("lemmas")){
    models=new Models(new SentenceModel(s),new TokenizerModel(t),new POSModel(p),new LemmatizerModel(l));
   }catch(IOException ex){throw new Unavailable("Modèles français OpenNLP indisponibles",ex);}
   return models;
  }
 }
 private static InputStream resource(String task)throws IOException{
  String path="/opennlp-fr-ud-gsd-"+task+"-1.3-2.5.4.bin";
  var in=FrenchNlpReader.class.getResourceAsStream(path);
  if(in==null)throw new FileNotFoundException(path);return in;
 }
 public List<Sentence> read(String source){
  if(source==null||source.isBlank()||source.length()>8000)throw new IllegalArgumentException("Texte requis, maximum 8000 caractères");
  var m=models();
  // Models are shared, mutable inference objects are deliberately invocation-local (OpenNLP 2.x).
  var detector=new SentenceDetectorME(m.sentences());var tokenizer=new TokenizerME(m.tokens());
  var tagger=new POSTaggerME(m.pos());var lemmatizer=new LemmatizerME(m.lemmas());
  var spans=detector.sentPosDetect(source);
  if(spans.length>32)throw new Limit("Plus de 32 phrases NLP");
  var result=new ArrayList<Sentence>();int total=0;
  for(var span:spans){
   String sentence=source.substring(span.getStart(),span.getEnd());var positions=tokenizer.tokenizePos(sentence);
   total+=positions.length;if(total>2048||positions.length>256)throw new Limit("Budget NLP : 2048 tokens, 256 par phrase");
   String[] words=Arrays.stream(positions).map(s->sentence.substring(s.getStart(),s.getEnd())).toArray(String[]::new);
   String[] pos=tagger.tag(words),lemmas=lemmatizer.lemmatize(words,pos);double[] scores=tagger.probs();
   var tokens=new ArrayList<Token>();
   for(int i=0;i<words.length;i++)tokens.add(new Token(span.getStart()+positions[i].getStart(),span.getStart()+positions[i].getEnd(),words[i],pos[i],lemmas[i],scores[i]));
   result.add(new Sentence(span.getStart(),span.getEnd(),tokens));
  }
  return List.copyOf(result);
 }
}
