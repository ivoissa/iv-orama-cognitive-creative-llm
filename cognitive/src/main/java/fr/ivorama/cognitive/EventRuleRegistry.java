package fr.ivorama.cognitive;
import java.util.*;

/** Schémas d'action déclaratifs : variables de rôle, préconditions, effets, aucune phrase réponse. */
public final class EventRuleRegistry {
 public record Literal(String relation,List<String> roles,boolean positive){public Literal{roles=List.copyOf(roles);}}
 public record Rule(String id,Map<String,String> roles,List<Literal> preconditions,List<Literal> effects){
  public Rule{roles=Map.copyOf(roles);preconditions=List.copyOf(preconditions);effects=List.copyOf(effects);}
 }
 /** Domaine par argument ; la valeur à functionalSlot est exclusive pour les autres arguments. */
 public record Relation(String id,List<String> domains,int functionalSlot){public Relation{domains=List.copyOf(domains);}}
 private final Map<String,Relation> relations;private final Map<String,Rule> rules;
 public EventRuleRegistry(List<Relation> relations,List<Rule> rules){
  Map<String,Relation> rs=new LinkedHashMap<>();Map<String,Rule> as=new LinkedHashMap<>();
  for(var r:relations){if(r.domains().isEmpty()||r.domains().size()>4||r.functionalSlot()<-1||r.functionalSlot()>=r.domains().size()||rs.put(r.id(),r)!=null)throw new IllegalArgumentException("Relation invalide");}
  for(var a:rules){if(as.put(a.id(),a)!=null)throw new IllegalArgumentException("Action dupliquée");
   for(var l:concat(a.preconditions(),a.effects())){var r=rs.get(l.relation());if(r==null||l.roles().size()!=r.domains().size())throw new IllegalArgumentException("Signature d'effet invalide");
    for(int i=0;i<l.roles().size();i++)if(!r.domains().get(i).equals(a.roles().get(l.roles().get(i))))throw new IllegalArgumentException("Rôle non typé");}
  }
  this.relations=Map.copyOf(rs);this.rules=Map.copyOf(as);
 }
 private static <T> List<T> concat(List<T>a,List<T>b){var r=new ArrayList<>(a);r.addAll(b);return r;}
 public Map<String,Relation> relations(){return relations;}public Map<String,Rule> rules(){return rules;}
 public static EventRuleRegistry defaults(){
  var held=new Relation("holds",List.of("agent","object"),0);
  var source=new Literal("holds",List.of("source","object"),true);
  var target=new Literal("holds",List.of("target","object"),true);
  return new EventRuleRegistry(List.of(held),List.of(
   new Rule("transfer",Map.of("source","agent","target","agent","object","object"),List.of(source),List.of(new Literal("holds",List.of("source","object"),false),target)),
   new Rule("acquire",Map.of("target","agent","object","object"),List.of(),List.of(target)),
   new Rule("release",Map.of("source","agent","object","object"),List.of(source),List.of(new Literal("holds",List.of("source","object"),false)))));
 }
}
