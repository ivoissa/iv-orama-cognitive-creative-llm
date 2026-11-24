package fr.ivorama.cognitive;
import java.util.*;

/** Traits et unification ; aucune suppression approximative de suffixe pour deviner un verbe. */
public final class FrenchAgreement {
 private FrenchAgreement(){}
 public enum Gender { UNKNOWN,MASCULINE,FEMININE }
 public enum GrammaticalNumber { UNKNOWN,SINGULAR,PLURAL }
 public record Features(Gender gender,GrammaticalNumber number){
  public Features{Objects.requireNonNull(gender);Objects.requireNonNull(number);}
  public Features unify(Features other){
   if(gender!=Gender.UNKNOWN&&other.gender!=Gender.UNKNOWN&&gender!=other.gender||number!=GrammaticalNumber.UNKNOWN&&other.number!=GrammaticalNumber.UNKNOWN&&number!=other.number)throw new IllegalArgumentException("Accord incompatible avec l'antécédent");
   return new Features(gender==Gender.UNKNOWN?other.gender:gender,number==GrammaticalNumber.UNKNOWN?other.number:number);
  }
 }
 public record Paradigm(String masculineSingular,String feminineSingular,String masculinePlural,String femininePlural){
  public List<Features> analyze(String form){
   List<Features> r=new ArrayList<>();
   if(form.equals(masculineSingular))r.add(new Features(Gender.MASCULINE,GrammaticalNumber.SINGULAR));
   if(form.equals(feminineSingular))r.add(new Features(Gender.FEMININE,GrammaticalNumber.SINGULAR));
   if(form.equals(masculinePlural))r.add(new Features(Gender.MASCULINE,GrammaticalNumber.PLURAL));
   if(form.equals(femininePlural))r.add(new Features(Gender.FEMININE,GrammaticalNumber.PLURAL));return List.copyOf(r);
  }
 }
 /** Deux classes productives couvertes : -é/-u et -is. Autres classes : déclaration explicite. */
 public static Paradigm paradigm(String base){
  if(base.matches("[\\p{L}-]+(?:é|u)"))return new Paradigm(base,base+"e",base+"s",base+"es");
  if(base.matches("[\\p{L}-]+is"))return new Paradigm(base,base+"e",base,base+"es");
  throw new IllegalArgumentException("Paradigme de participe non déclaré");
 }
 public static Features determiner(String determiner){return switch(determiner.strip()){
  case "le","un"->new Features(Gender.MASCULINE,GrammaticalNumber.SINGULAR);
  case "la","une"->new Features(Gender.FEMININE,GrammaticalNumber.SINGULAR);
  case "les","des"->new Features(Gender.UNKNOWN,GrammaticalNumber.PLURAL);
  case "l'"->new Features(Gender.UNKNOWN,GrammaticalNumber.SINGULAR);
  default->throw new IllegalArgumentException("Déterminant sans traits déclarés");};}
 public static Features agree(Paradigm p,String form,Features object){
  List<Features> compatible=new ArrayList<>();for(var features:p.analyze(form))try{compatible.add(object.unify(features));}catch(IllegalArgumentException ignored){}
  if(compatible.isEmpty())throw new IllegalArgumentException("Participe passé incompatible avec le COD antéposé");
  Gender gender=compatible.get(0).gender();GrammaticalNumber number=compatible.get(0).number();
  for(var f:compatible){if(f.gender()!=gender)gender=Gender.UNKNOWN;if(f.number()!=number)number=GrammaticalNumber.UNKNOWN;}
  return new Features(gender,number);
 }
}
