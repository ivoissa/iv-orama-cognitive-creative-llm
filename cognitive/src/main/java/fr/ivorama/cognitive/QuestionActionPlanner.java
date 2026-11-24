package fr.ivorama.cognitive;
import java.util.Objects;
/** Règles explicites d'orientation RDO ; aucune réponse empirique produite ici. */
public final class QuestionActionPlanner {
 public enum Action { USE_EXISTING_ENGINE, REQUEST_LOCATION, REQUEST_DATE,
     REQUEST_OBSERVATION, SEARCH_DOCUMENTS, ANALYZE_CONTEXT, CLARIFY_INTENT }
 public record Plan(Action action,String reason) {}
 public Plan plan(FrenchQuestionAnalyzer.Analysis a){
  Objects.requireNonNull(a);
  if(a.kind()==FrenchQuestionAnalyzer.Kind.CONTEXTUAL_VISIBILITY)return new Plan(Action.ANALYZE_CONTEXT,"Évaluer les conditions et les contre-modèles avant toute recherche");
  if(a.kind()==FrenchQuestionAnalyzer.Kind.UNSUPPORTED)return new Plan(Action.CLARIFY_INTENT,"Intention ou portée hors du fragment");
  if(a.kind()==FrenchQuestionAnalyzer.Kind.INDIVIDUAL_STATE)return new Plan(Action.REQUEST_OBSERVATION,"État d'un individu : des observations sont nécessaires");
  if(a.kind()==FrenchQuestionAnalyzer.Kind.DATED_VALUE){
   if(a.missing().contains(FrenchQuestionAnalyzer.Need.LOCATION))return new Plan(Action.REQUEST_LOCATION,"Lieu non fourni");
   if(a.missing().contains(FrenchQuestionAnalyzer.Need.DATE))return new Plan(Action.REQUEST_DATE,"Date non fournie");
   return new Plan(Action.SEARCH_DOCUMENTS,"Rechercher une valeur datée, sans confondre document et mesure");
  }
  if(a.ontologyCompatible())return new Plan(Action.USE_EXISTING_ENGINE,"Relation compatible avec le moteur historique");
  return new Plan(Action.SEARCH_DOCUMENTS,"Propriété ou justification documentaire sans inférence typée disponible");
 }
}
