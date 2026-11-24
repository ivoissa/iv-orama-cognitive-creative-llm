package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.SemanticIntermediateRepresentation.EntityRef;

/** Enveloppe JSON, sans modification du format --structured existant. */
public record StructuredWebRequest(StructuredLogic.Problem problem,StructuredWebLexicon lexicon){
 public static StructuredWebRequest parse(String json){
  if(json==null||json.length()>65536)throw new IllegalArgumentException("Requête absente ou trop longue");
  var root=obj(MiniJson.parse(json));keys(root,"problem","lexicon");
  var problem=StructuredProblemJson.parseObject(obj(root.get("problem")));var lex=obj(root.get("lexicon"));keys(lex,"entities","predicates");
  Map<EntityRef,String> entities=new HashMap<>();Map<String,StructuredWebLexicon.Surface> predicates=new HashMap<>();
  for(var raw:arr(lex.get("entities"))){var m=obj(raw);keys(m,"kind","name","surface");var e=new EntityRef(str(m.get("kind")),str(m.get("name")));
   if(!problem.entities().contains(e)||entities.putIfAbsent(e,str(m.get("surface")))!=null)throw new IllegalArgumentException("Entité lexicale non déclarée ou dupliquée");}
  for(var raw:arr(lex.get("predicates"))){var m=obj(raw);keys(m,"name","positive","negative","scope");String name=str(m.get("name"));
   if(problem.predicates().stream().noneMatch(p->p.name().equals(name)))throw new IllegalArgumentException("Prédicat lexical non déclaré");
   var surface=new StructuredWebLexicon.Surface(str(m.get("positive")),str(m.get("negative")),StructuredWebLexicon.Scope.valueOf(str(m.get("scope"))));
   if(predicates.putIfAbsent(name,surface)!=null)throw new IllegalArgumentException("Prédicat lexical dupliqué");}
  return new StructuredWebRequest(problem,new StructuredWebLexicon(entities,predicates));
 }
 @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object x){if(!(x instanceof Map<?,?>))throw new IllegalArgumentException("Objet JSON requis");return (Map<String,Object>)x;}
 @SuppressWarnings("unchecked") private static List<Object> arr(Object x){if(!(x instanceof List<?>))throw new IllegalArgumentException("Liste JSON requise");return (List<Object>)x;}
 private static String str(Object x){if(!(x instanceof String s)||s.isBlank())throw new IllegalArgumentException("Texte obligatoire");return (String)x;}
 private static void keys(Map<String,Object> m,String... allowed){if(!Set.of(allowed).containsAll(m.keySet()))throw new IllegalArgumentException("Champ JSON inconnu");}
}
