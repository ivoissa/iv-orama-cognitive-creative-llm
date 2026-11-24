package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.CreativeTextEngine.*;

/** Grammaire nominale singulière : accords et élision ; réanalyse de la surface sans accès au plan. */
public final class FrenchTextRealizer {
 private FrenchTextRealizer(){}
 public static String realize(Clause c,Vocabulary v){
  Noun s=v.noun(c.subject());Verb verb=v.verb(c.verb());String text=definite(s)+" "+verb.surface();
  if(c.object().isPresent()){
   Noun o=v.noun(c.object().get());var a=v.adjective(c.adjective().orElseThrow());
   String adjective=o.gender()==FrenchAgreement.Gender.FEMININE?a.forms().feminineSingular():a.forms().masculineSingular();
   text+=" "+indefinite(o)+" "+adjective;
  }
  return text.substring(0,1).toUpperCase(Locale.ROOT)+text.substring(1)+".";
 }
 public static List<Clause> read(String text,Vocabulary v){
  if(text==null||text.length()>4000)throw new IllegalArgumentException("Surface hors budget");
  List<Clause> result=new ArrayList<>();
  for(String line:text.split("\\n",-1)){
   if(!line.endsWith(".")||line.endsWith(".."))throw new IllegalArgumentException("Terminaison non conforme");
   String body=line.substring(0,line.length()-1).toLowerCase(Locale.ROOT);List<Clause> matches=new ArrayList<>();
   for(var subject:v.nouns()){
    String prefix=definite(subject)+" ";if(!body.startsWith(prefix))continue;String rest=body.substring(prefix.length());
    for(var verb:v.verbs()){
     if(rest.equals(verb.surface())&&!verb.transitive())matches.add(new Clause(subject.id(),verb.id(),Optional.empty(),Optional.empty()));
     if(!verb.transitive()||!rest.startsWith(verb.surface()+" "))continue;
     String tail=rest.substring(verb.surface().length()+1);
     for(var object:v.nouns()){
      String objectPrefix=indefinite(object)+" ";if(!tail.startsWith(objectPrefix))continue;String form=tail.substring(objectPrefix.length());
      for(var adjective:v.adjectives())if(adjective.forms().analyze(form).stream().anyMatch(f->f.gender()==object.gender()&&f.number()==FrenchAgreement.GrammaticalNumber.SINGULAR))
       matches.add(new Clause(subject.id(),verb.id(),Optional.of(object.id()),Optional.of(adjective.id())));
     }
    }
   }
   if(matches.size()!=1)throw new IllegalArgumentException("Surface non interprétable sans ambiguïté par la grammaire déclarée");
   result.add(matches.get(0));if(result.size()>4)throw new IllegalArgumentException("Plus de quatre propositions");
  }
  return List.copyOf(result);
 }
 private static String definite(Noun n){return "aàâeéèêëiîïoôuùûüyœ".indexOf(n.word().charAt(0))>=0?"l'"+n.word():(n.gender()==FrenchAgreement.Gender.FEMININE?"la ":"le ")+n.word();}
 private static String indefinite(Noun n){return (n.gender()==FrenchAgreement.Gender.FEMININE?"une ":"un ")+n.word();}
}
