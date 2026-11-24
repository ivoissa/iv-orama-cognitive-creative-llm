package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.SemanticIntermediateRepresentation.EntityRef;

/** Contrat symbolique fini : noms de domaine fournis par l'appelant, aucune connaissance implicite. */
public final class StructuredLogic {
 private StructuredLogic(){}
 public static String identifier(String s){
  Objects.requireNonNull(s);if(!s.matches("[a-z][a-z0-9_]{0,63}"))throw new IllegalArgumentException("Identifiant symbolique invalide");return s;
 }
 public sealed interface Term permits Constant,Variable {}
 public record Constant(EntityRef entity) implements Term {public Constant{Objects.requireNonNull(entity);}}
 public record Variable(String name) implements Term {public Variable{name=identifier(name);}}
 public record Predicate(String name,List<String> kinds){
  public Predicate{name=identifier(name);kinds=List.copyOf(kinds);if(kinds.isEmpty()||kinds.size()>4)throw new IllegalArgumentException("Arité 1 à 4 requise");kinds.forEach(StructuredLogic::identifier);}
 }
 public sealed interface Expression permits Atom,Not,And,Implies,ForAll,Exists {}
 public record Atom(String predicate,List<Term> arguments) implements Expression {
  public Atom{predicate=identifier(predicate);arguments=List.copyOf(arguments);}
 }
 public record Not(Expression body) implements Expression {public Not{Objects.requireNonNull(body);}}
 public record And(Expression left,Expression right) implements Expression {public And{Objects.requireNonNull(left);Objects.requireNonNull(right);}}
 public record Implies(Expression premise,Expression conclusion) implements Expression {public Implies{Objects.requireNonNull(premise);Objects.requireNonNull(conclusion);}}
 public record ForAll(String variable,String kind,Expression body) implements Expression {
  public ForAll{variable=identifier(variable);kind=identifier(kind);Objects.requireNonNull(body);}
 }
 public record Exists(String variable,String kind,Expression body) implements Expression {
  public Exists{variable=identifier(variable);kind=identifier(kind);Objects.requireNonNull(body);}
 }
 public enum Origin { DECLARATION, DOMAIN_RULE, HYPOTHESIS, WEB_EXCERPT_UNVERIFIED }
 public record Evidence(Origin origin,String source,String excerpt){
  public Evidence{Objects.requireNonNull(origin);if(source==null||source.isBlank()||excerpt==null||excerpt.isBlank())throw new IllegalArgumentException("Provenance et extrait obligatoires");}
 }
 public record Premise(String id,Expression expression,Evidence evidence){
  public Premise{id=identifier(id);Objects.requireNonNull(expression);Objects.requireNonNull(evidence);}
 }
 public record Observable(Expression expression,int cost){
  public Observable{Objects.requireNonNull(expression);if(cost<=0)throw new IllegalArgumentException("Coût positif requis");}
 }
 public record Problem(String sourceText,List<EntityRef> entities,List<Predicate> predicates,
                       List<Premise> premises,Expression goal,List<Observable> observables,List<String> unresolved){
  public Problem{
   if(sourceText==null||sourceText.isBlank())throw new IllegalArgumentException("Énoncé obligatoire");
   entities=List.copyOf(entities);predicates=List.copyOf(predicates);premises=List.copyOf(premises);
   Objects.requireNonNull(goal);observables=List.copyOf(observables);unresolved=List.copyOf(unresolved);
  }
 }
 public record GroundAtom(String predicate,List<EntityRef> arguments){
  public GroundAtom{arguments=List.copyOf(arguments);}
 }
}
