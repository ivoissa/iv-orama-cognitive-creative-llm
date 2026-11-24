package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;

/** Sérialiseur du contrat existant ; aucune règle linguistique ni réponse. */
public final class StructuredJsonWriter {
 private StructuredJsonWriter(){}
 public static String write(Problem p){
  Map<String,Object> root=new LinkedHashMap<>();root.put("sourceText",p.sourceText());
  root.put("entities",p.entities().stream().map(e->Map.of("kind",e.kind(),"name",e.name())).toList());
  root.put("predicates",p.predicates().stream().map(d->Map.of("name",d.name(),"kinds",d.kinds())).toList());
  root.put("premises",p.premises().stream().map(pr->{Map<String,Object> m=new LinkedHashMap<>();m.put("id",pr.id());m.put("origin",pr.evidence().origin().name());m.put("source",pr.evidence().source());m.put("excerpt",pr.evidence().excerpt());m.put("formula",expression(pr.expression(),0));return m;}).toList());
  root.put("goal",expression(p.goal(),0));root.put("observables",p.observables().stream().map(o->Map.of("formula",expression(o.expression(),0),"cost",o.cost())).toList());root.put("unresolved",p.unresolved());return json(root);
 }
 public static Object expressionValue(Expression e){return expression(e,0);}
 private static Object expression(Expression e,int depth){
  if(depth>64)throw new IllegalArgumentException("Expression trop profonde pour JSON");
  if(e instanceof Atom a)return Map.of("atom",a.predicate(),"args",a.arguments().stream().map(t->t instanceof Constant c?c.entity().name():"?"+((Variable)t).name()).toList());
  if(e instanceof Not n)return Map.of("not",expression(n.body(),depth+1));
  if(e instanceof And a)return Map.of("and",List.of(expression(a.left(),depth+1),expression(a.right(),depth+1)));
  if(e instanceof Implies i)return Map.of("implies",List.of(expression(i.premise(),depth+1),expression(i.conclusion(),depth+1)));
  if(e instanceof ForAll q)return Map.of("forall",Map.of("variable",q.variable(),"kind",q.kind(),"body",expression(q.body(),depth+1)));
  var q=(Exists)e;return Map.of("exists",Map.of("variable",q.variable(),"kind",q.kind(),"body",expression(q.body(),depth+1)));
 }
 public static String json(Object x){return encode(x,0);}
 private static String encode(Object x,int depth){
  if(depth>128)throw new IllegalArgumentException("JSON trop profond");
  if(x instanceof String s){StringBuilder b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format("\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}
  if(x instanceof Map<?,?> m){StringJoiner out=new StringJoiner(",","{","}");m.forEach((k,v)->out.add(encode((String)k,depth+1)+":"+encode(v,depth+1)));return out.toString();}
  if(x instanceof Collection<?> xs){StringJoiner out=new StringJoiner(",","[","]");xs.forEach(v->out.add(encode(v,depth+1)));return out.toString();}
  if(x instanceof Integer||x instanceof Boolean)return x.toString();throw new IllegalArgumentException("Valeur JSON non supportée");
 }
}
