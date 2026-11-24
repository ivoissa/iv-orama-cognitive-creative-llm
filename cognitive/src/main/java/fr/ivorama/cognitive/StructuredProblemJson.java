package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;
import static fr.ivorama.cognitive.SemanticIntermediateRepresentation.EntityRef;

/** Entrée structurée stricte, pas un analyseur du français. Les champs inconnus sont refusés. */
public final class StructuredProblemJson {
 private StructuredProblemJson(){}
 public static Problem parse(String json){
  if(json==null||json.length()>65536)throw new IllegalArgumentException("JSON absent ou trop long");
  return parseObject(obj(MiniJson.parse(json)));
 }
 public static Problem parseObject(Map<String,Object> root){
  keys(root,"sourceText","entities","predicates","premises","goal","observables","unresolved");
  List<EntityRef> entities=new ArrayList<>();
  for(Object e:array(root,"entities")){var m=obj(e);keys(m,"kind","name");entities.add(new EntityRef(string(m,"kind"),string(m,"name")));}
  List<Predicate> predicates=new ArrayList<>();Map<String,Predicate> defs=new HashMap<>();
  for(Object v:array(root,"predicates")){
   var m=obj(v);keys(m,"name","kinds");List<String> kinds=new ArrayList<>();for(var k:array(m,"kinds"))kinds.add(string(k));
   var p=new Predicate(string(m,"name"),kinds);if(defs.putIfAbsent(p.name(),p)!=null)throw new IllegalArgumentException("Prédicat dupliqué");predicates.add(p);
  }
  List<Premise> premises=new ArrayList<>();
  for(Object v:array(root,"premises")){
   var m=obj(v);keys(m,"id","origin","source","excerpt","formula");
   var evidence=new Evidence(Origin.valueOf(string(m,"origin")),string(m,"source"),string(m,"excerpt"));
   premises.add(new Premise(string(m,"id"),expr(m.get("formula"),defs,0),evidence));
  }
  List<StructuredLogic.Observable> observables=new ArrayList<>();
  for(Object v:optionalArray(root,"observables")){
   var m=obj(v);keys(m,"formula","cost");Object raw=m.get("cost");
   if(!(raw instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Coût entier requis");
   observables.add(new StructuredLogic.Observable(expr(m.get("formula"),defs,0),((Number)raw).intValue()));
  }
  List<String> unresolved=new ArrayList<>();for(Object x:optionalArray(root,"unresolved"))unresolved.add(string(x));
  return new Problem(string(root,"sourceText"),entities,predicates,premises,expr(root.get("goal"),defs,0),observables,unresolved);
 }
 static Expression readExpression(Object raw,List<Predicate> predicates){
  Map<String,Predicate> defs=new HashMap<>();for(var p:predicates)if(defs.putIfAbsent(p.name(),p)!=null)throw new IllegalArgumentException("Prédicat dupliqué");
  return expr(raw,defs,0);
 }
 private static Expression expr(Object raw,Map<String,Predicate> defs,int depth){
  if(depth>64)throw new IllegalArgumentException("Expression trop profonde");var m=obj(raw);
  if(m.containsKey("atom")){
   keys(m,"atom","args");String name=string(m,"atom");Predicate def=defs.get(name);
   if(def==null)throw new IllegalArgumentException("Prédicat non déclaré");
   var args=array(m,"args");if(args.size()!=def.kinds().size())throw new IllegalArgumentException("Arité incorrecte");
   List<Term> terms=new ArrayList<>();
   for(int i=0;i<args.size();i++){String value=string(args.get(i));terms.add(value.startsWith("?")?new Variable(value.substring(1)):new Constant(new EntityRef(def.kinds().get(i),value)));}
   return new Atom(name,terms);
  }
  if(m.size()!=1)throw new IllegalArgumentException("Un opérateur requis par expression");
  if(m.containsKey("not"))return new Not(expr(m.get("not"),defs,depth+1));
  for(String op:List.of("and","implies"))if(m.containsKey(op)){
   var xs=array(m,op);if(xs.size()!=2)throw new IllegalArgumentException("Opérateur binaire requis");
   var a=expr(xs.get(0),defs,depth+1);var b=expr(xs.get(1),defs,depth+1);return op.equals("and")?new And(a,b):new Implies(a,b);
  }
  for(String op:List.of("forall","exists"))if(m.containsKey(op)){
   var q=obj(m.get(op));keys(q,"variable","kind","body");var body=expr(q.get("body"),defs,depth+1);
   return op.equals("forall")?new ForAll(string(q,"variable"),string(q,"kind"),body):new Exists(string(q,"variable"),string(q,"kind"),body);
  }
  throw new IllegalArgumentException("Opérateur non supporté");
 }
 @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object x){if(!(x instanceof Map<?,?>))throw new IllegalArgumentException("Objet JSON requis");return (Map<String,Object>)x;}
 @SuppressWarnings("unchecked") private static List<Object> array(Map<String,Object> m,String k){if(!(m.get(k) instanceof List<?>))throw new IllegalArgumentException("Liste requise : "+k);return (List<Object>)m.get(k);}
 private static List<Object> optionalArray(Map<String,Object> m,String k){return m.containsKey(k)?array(m,k):List.of();}
 private static String string(Object v){if(!(v instanceof String s)||s.isBlank())throw new IllegalArgumentException("Texte non vide requis");return (String)v;}
 private static String string(Map<String,Object> m,String k){return string(m.get(k));}
 private static void keys(Map<String,Object> m,String... allowed){if(!Set.of(allowed).containsAll(m.keySet()))throw new IllegalArgumentException("Champ JSON inconnu");}
}
