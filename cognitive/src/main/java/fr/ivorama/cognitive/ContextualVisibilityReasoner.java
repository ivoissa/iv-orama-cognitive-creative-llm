package fr.ivorama.cognitive;

import java.util.*;
import java.util.regex.*;

/** Petit modèle de visibilité directe, conditionnel aux déclarations, sans faits appris. */
public final class ContextualVisibilityReasoner {
 public enum Variable { EXTERNAL, OPENING, TRANSPARENT, ALIGNED, TARGET_AVAILABLE, OBSERVER_ABLE }
 public enum Finding { UNDETERMINED, IMPOSSIBLE_CONDITIONALLY, POSSIBLE_CONDITIONALLY, INCONSISTENT, NEEDS_INTERPRETATION }
 public record Constraint(Variable variable,boolean value,String premise) {}
 public record Frame(List<String> premises,String question,String target,boolean enclosed,
                     List<Constraint> constraints,List<String> uninterpreted) {
  public Frame {premises=List.copyOf(premises);constraints=List.copyOf(constraints);uninterpreted=List.copyOf(uninterpreted);}
 }
 public record Model(Map<Variable,Boolean> values,boolean directVisibility) {
  public Model {values=Map.copyOf(values);}
 }
 public record Assessment(Finding finding,int admissibleModels,Optional<Model> visible,
                          Optional<Model> invisible,String explanation) {}
 private static final String TERM="[a-z][a-z' -]{0,79}";
 private static final Pattern QUESTION=Pattern.compile("^(?:(?:est[ -]ce que )?je vois|puis-je voir|est[ -]ce que je peux voir) (?:directement )?((?:le |la |les |un |une |l')"+TERM+")$");
 public Optional<Frame> analyze(String input){
  String text=FrenchQuestionAnalyzer.normalize(input).replaceAll("[?!]+$", "").strip();
  Matcher split=Pattern.compile("^(.+?)[;.,]\\s*((?:(?:est[ -]ce que )?je vois|puis-je voir|est[ -]ce que je peux voir) .+)$").matcher(text);
  String context="",question=text;
  if(split.matches()){context=split.group(1);question=split.group(2);}
  Matcher q=QUESTION.matcher(question);if(!q.matches())return Optional.empty();
  String target=q.group(1).replaceFirst("^(?:le |la |les |un |une |l')", "").strip();
  if(target.matches(".*\\b(?:pas|sans|et|ou|si|mais|jamais)\\b.*"))return Optional.empty();
  List<String> premises=context.isEmpty()?List.of():Arrays.stream(context.split("\\s*[;.]+\\s*|\\s+et\\s+")).map(String::strip).filter(p->!p.isEmpty()).toList();
  List<Constraint> cs=new ArrayList<>();List<String> unknown=new ArrayList<>();boolean enclosed=false;
  // Hypothèse de domaine explicite : ces objets célestes sont extérieurs à l'enceinte.
  if(Set.of("soleil","lune","etoiles").contains(target))cs.add(new Constraint(Variable.EXTERNAL,true,"Hypothèse de domaine : objet céleste extérieur"));
  for(String p:premises){
   if(p.startsWith("je suis dans ")){
    String place=p.substring(13).replaceFirst("^(?:une |un |le |la |l')", "").replaceFirst(" sans fenetres?$", "");
    if(place.matches("[a-z]+(?: [a-z]+){0,3}") && !place.matches(".*\\b(?:sans|pas|ne|avec|si|mais|qui|que|aucune|aucun|ni)\\b.*"))enclosed=true;
    else unknown.add(p);
   }
   else if(p.equals("toutes les parois sont opaques"))cs.add(new Constraint(Variable.TRANSPARENT,false,p));
   else if(p.equals("il n'y a aucune ouverture") || p.equals("aucune ouverture n'existe"))cs.add(new Constraint(Variable.OPENING,false,p));
   else if(p.equals("une porte est ouverte") || p.equals("la porte est ouverte"))cs.add(new Constraint(Variable.OPENING,true,p));
   else if(p.equals("une paroi est transparente"))cs.add(new Constraint(Variable.TRANSPARENT,true,p));
   else if(p.equals("la ligne de visee est libre"))cs.add(new Constraint(Variable.ALIGNED,true,p));
   else if(p.equals("la ligne de visee est bloquee"))cs.add(new Constraint(Variable.ALIGNED,false,p));
   else if(p.equals("la cible est visible a cet instant"))cs.add(new Constraint(Variable.TARGET_AVAILABLE,true,p));
   else if(p.equals("je peux voir normalement"))cs.add(new Constraint(Variable.OBSERVER_ABLE,true,p));
   else if(p.equals("la cible est dehors") || p.equals("l'objet est dehors"))cs.add(new Constraint(Variable.EXTERNAL,true,p));
   else if(p.equals("la cible est dedans") || p.equals("l'objet est dedans"))cs.add(new Constraint(Variable.EXTERNAL,false,p));
   else unknown.add(p);
  }
  return Optional.of(new Frame(premises,question,target,enclosed,cs,unknown));
 }
 public Assessment assess(Frame frame){
  if(!frame.uninterpreted().isEmpty())return result(Finding.NEEDS_INTERPRETATION,0,null,null,
   "Contexte séparé de la question, mais prémisses non interprétées : "+frame.uninterpreted()+". Reformule ; aucune recherche Web ni conclusion.");
  if(!frame.enclosed())return result(Finding.NEEDS_INTERPRETATION,0,null,null,
   "La question concerne la visibilité directe. Précise ta position et les conditions d'observation ; aucune scène n'est supposée.");
  List<Model> models=new ArrayList<>();
  for(int bits=0;bits<64;bits++){
   Map<Variable,Boolean> values=new EnumMap<>(Variable.class);
   for(var v:Variable.values())values.put(v,(bits&(1<<v.ordinal()))!=0);
   if(frame.constraints().stream().anyMatch(c->values.get(c.variable())!=c.value()))continue;
   boolean path=!values.get(Variable.EXTERNAL)||values.get(Variable.OPENING)||values.get(Variable.TRANSPARENT);
   boolean visible=path&&values.get(Variable.ALIGNED)&&values.get(Variable.TARGET_AVAILABLE)&&values.get(Variable.OBSERVER_ABLE);
   models.add(new Model(values,visible));
  }
  Model yes=models.stream().filter(Model::directVisibility).findFirst().orElse(null);
  Model no=models.stream().filter(m->!m.directVisibility()).findFirst().orElse(null);
  String prefix="Prémisses rapportées, non vérifiées : "+frame.premises()+". Question : voir directement « "+frame.target()+" ». ";
  if(models.isEmpty())return result(Finding.INCONSISTENT,0,null,null,prefix+"Les conditions déclarées sont incompatibles ; aucune conclusion.");
  if(yes==null)return result(Finding.IMPOSSIBLE_CONDITIONALLY,models.size(),null,no,prefix+
   "Dans ce modèle et sous ces prémisses, la visibilité directe est impossible. Cela ne concerne ni image, ni écran, ni réflexion ; aucune scène réelle n'est certifiée.");
  if(no==null)return result(Finding.POSSIBLE_CONDITIONALLY,models.size(),yes,null,prefix+
   "Les conditions de visibilité directe sont satisfaites dans le modèle ; ce résultat reste conditionnel aux déclarations et ne certifie pas une observation réelle.");
  return result(Finding.UNDETERMINED,models.size(),yes,no,prefix+
   "Impossible de trancher : il existe un modèle compatible où la cible est visible et un autre où elle ne l'est pas. L'absence de fenêtre seule n'exclut pas une porte ou une autre ouverture. "+
   "Précise les ouvertures, l'opacité des parois, la ligne de visée et les conditions d'observation. Une ouverture seule ne garantit pas de voir la cible. Aucune recherche Web nécessaire pour cette indétermination.");
 }
 private static Assessment result(Finding f,int n,Model yes,Model no,String text){return new Assessment(f,n,Optional.ofNullable(yes),Optional.ofNullable(no),text);}
}
