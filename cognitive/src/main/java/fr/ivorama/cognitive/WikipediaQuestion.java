package fr.ivorama.cognitive;
import java.util.*;
import java.util.regex.*;

/** Intention documentaire générique, sans dictionnaire de réponses ni réécriture sémantique. */
public final class WikipediaQuestion {
 private WikipediaQuestion(){}
 public enum Intent { DEFINITION,SEARCH }
 public record Request(String sourceText,Intent intent,String terms){
  public Request{Objects.requireNonNull(intent);if(sourceText==null||sourceText.isBlank()||sourceText.length()>8000||terms==null||terms.isBlank()||terms.length()>180||terms.chars().anyMatch(c->c<32)||terms.matches(".*[?!;].*"))throw new IllegalArgumentException("Sujet documentaire absent, ambigu ou hors budget");}
 }
 private static final Pattern DEFINITION=Pattern.compile("^(?:qu'est[- ]ce qu(?:e\\s+|')|c'est quoi\\s+|définis\\s+|définition (?:de\\s+|d'))(.+)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
 private static final Pattern SEARCH=Pattern.compile("^(?:cherche|recherche) sur wikip[ée]dia\\s*:?\\s*(.*)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
 private static String normalize(String s){return s==null?"":s.strip().replace('’','\'').replaceAll("[ \\t]+"," ");}
 public static boolean isCandidate(String text){String s=normalize(text);return DEFINITION.matcher(s).matches()||SEARCH.matcher(s).matches();}
 public static Request parse(String text){
  String s=normalize(text);s=s.replaceFirst("\\s*\\?$","");Matcher search=SEARCH.matcher(s);
  if(search.matches())return new Request(text,Intent.SEARCH,search.group(1).strip());
  Matcher definition=DEFINITION.matcher(s);if(!definition.matches())throw new IllegalArgumentException("Intention de définition ou de recherche Wikipédia non reconnue");
  String terms=definition.group(1).strip().replaceFirst("(?iu)^(?:(?:un|une|le|la|les|des)\\s+|l')","");
  return new Request(text,Intent.DEFINITION,terms);
 }
 public static Request search(String terms){return new Request(terms,Intent.SEARCH,normalize(terms));}
 public static String write(Request r){return StructuredJsonWriter.json(Map.of("schema","ivoc.wikipedia.1","sourceText",r.sourceText(),"intent",r.intent().name(),"terms",r.terms(),"language","fr"));}
 public static Request read(String json){
  if(json.length()>16000)throw new IllegalArgumentException("Requête JSON trop grande");Object raw=MiniJson.parse(json);
  if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(Set.of("schema","sourceText","intent","terms","language"))||!"ivoc.wikipedia.1".equals(m.get("schema"))||!"fr".equals(m.get("language")))throw new IllegalArgumentException("Contrat Wikipédia invalide");
  return new Request(string(m.get("sourceText")),Intent.valueOf(string(m.get("intent"))),string(m.get("terms")));
 }
 private static String string(Object x){if(!(x instanceof String s))throw new IllegalArgumentException("Chaîne requise");return s;}
}
