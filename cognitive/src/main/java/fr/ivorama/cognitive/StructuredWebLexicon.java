package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.StructuredLogic.*;
import static fr.ivorama.cognitive.SemanticIntermediateRepresentation.EntityRef;

/** Lexique déclaratif de formes, jamais de valeurs de vérité. Contrat volontairement strict. */
public record StructuredWebLexicon(Map<EntityRef,String> entities,Map<String,Surface> predicates){
 public enum Scope { DOCUMENTARY }
 public record Surface(String positive,String negative,Scope scope){
  public Surface{Objects.requireNonNull(scope);check(positive);check(negative);if(normalize(positive).equals(normalize(negative)))throw new IllegalArgumentException("Polarités lexicales identiques");}
 }
 public StructuredWebLexicon{entities=Map.copyOf(entities);predicates=Map.copyOf(predicates);entities.values().forEach(StructuredWebLexicon::check);}
 private static void check(String text){if(text==null||text.isBlank()||text.length()>180||text.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Surface lexicale invalide");}
 public static String normalize(String s){return OntologicalKnowledgeGraph.normalized(s).replaceAll("[.!?]+$", "").strip();}
 public Optional<String> render(GroundAtom atom,boolean positive){
  var surface=predicates.get(atom.predicate());if(surface==null)return Optional.empty();
  String template=positive?surface.positive():surface.negative();String text=template;
  for(int i=0;i<atom.arguments().size();i++){
   String placeholder="{"+i+"}";
   if(template.indexOf(placeholder)<0||template.indexOf(placeholder)!=template.lastIndexOf(placeholder))return Optional.empty();
   String label=entities.get(atom.arguments().get(i));if(label==null||label.contains("{")||label.contains("}"))return Optional.empty();
   text=text.replace(placeholder,label);
  }
  if(text.contains("{")||text.contains("}")||text.length()>180)return Optional.empty();return Optional.of(text);
 }
 /** Une phrase entière doit identifier un unique atome ET une unique polarité. */
 public Optional<Literal> extract(String excerpt,Collection<GroundAtom> vocabulary){
  if(excerpt.contains("?")||excerpt.contains("!")||excerpt.strip().endsWith(".."))return Optional.empty();
  String text=normalize(excerpt);List<Literal> matches=new ArrayList<>();
  for(var a:vocabulary)for(boolean polarity:List.of(true,false))if(render(a,polarity).map(StructuredWebLexicon::normalize).filter(text::equals).isPresent())matches.add(new Literal(a,polarity));
  return matches.size()==1?Optional.of(matches.get(0)):Optional.empty();
 }
 public record Literal(GroundAtom atom,boolean positive){
  public Expression expression(){var a=new Atom(atom.predicate(),atom.arguments().stream().map(e->(Term)new Constant(e)).toList());return positive?a:new Not(a);}
 }
}
