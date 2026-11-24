package fr.ivorama.cognitive;
import java.util.*;
import java.util.regex.*;
/** Constructions définitoires générales ; le texte cité reste une donnée. */
public final class DefinitionIntent {
 private DefinitionIntent(){}
 public static Optional<String> term(DirectiveActs.Directive d){
  if(d.kind()!=DirectiveActs.Kind.EXPLAIN)return Optional.empty();
  String object=d.object().strip().replace('’','\'');
  if(d.verb().equals("définir"))return clean(object);
  var m=Pattern.compile("^(?:(?:moi|nous)\\s+)?(?:ce\\s+que\\s+(?:signifie|veut dire)|(?:le sens|la signification|la définition)\\s+(?:de|du)|(?:le concept|la notion)\\s+de)\\s+(.+?)\\s*[?.!]*$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(object);
  return m.matches()?clean(m.group(1)):Optional.empty();
 }
 private static Optional<String> clean(String value){
  String term=value.strip().replaceFirst("^(?i:(?:le|la|les|un|une)\\s+|l')(?=\\p{Ll})","");
  return term.isBlank()||term.length()>180||term.startsWith("«")||term.startsWith("\"")||term.chars().anyMatch(c->c<32)?Optional.empty():Optional.of(term);
 }
}
