package fr.ivorama.cognitive;
import java.util.*;

/** Durées exactes positives, bornées ; pas de lien implicite entre durée et effet causal. */
public final class FrenchRelativeTime {
 private FrenchRelativeTime(){}
 private static final Map<String,Integer> WORDS=words();
 private static Map<String,Integer> words(){Map<String,Integer> map=new HashMap<>();for(int n=0;n<=999;n++)map.put(normalize(spell(n)),n);map.put("une",1);map.put("zero",0);return Map.copyOf(map);}
 private static String normalize(String s){return s.strip().toLowerCase(Locale.ROOT).replace('-',' ').replaceAll("\\s+"," ");}
 private static String spell(int n){
  String[] small={"zéro","un","deux","trois","quatre","cinq","six","sept","huit","neuf","dix","onze","douze","treize","quatorze","quinze","seize"};
  if(n<=16)return small[n];if(n<20)return "dix-"+spell(n-10);
  if(n<70){String[] tens={"","","vingt","trente","quarante","cinquante","soixante"};return tens[n/10]+(n%10==0?"":n%10==1?" et un":"-"+spell(n%10));}
  if(n<80)return "soixante"+(n==71?" et ":"-")+spell(n-60);
  if(n<100)return n==80?"quatre-vingts":"quatre-vingt-"+spell(n-80);
  return (n/100==1?"cent":spell(n/100)+" cent"+(n%100==0?"s":""))+(n%100==0?"":" "+spell(n%100));
 }
 public static long seconds(String quantity,String unit){
  String q=normalize(quantity);long count;
  if(q.matches("[0-9]{1,8}"))count=Long.parseLong(q);else {Integer n=WORDS.get(q);if(n==null)throw new IllegalArgumentException("Nombre français hors fragment cardinal 0..999");count=n;}
  long multiplier=switch(unit){case "seconde","secondes"->1;case "minute","minutes"->60;case "heure","heures"->3600;case "jour","jours"->86400;default->throw new IllegalArgumentException("Unité de durée inconnue");};
  long result=Math.multiplyExact(count,multiplier);if(result>31_536_000)throw new IllegalArgumentException("Repère temporel au-delà de 365 jours");return result;
 }
}
