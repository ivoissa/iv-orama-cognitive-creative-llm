package fr.ivorama.cognitive.generative;

import fr.ivorama.cognitive.*;
import java.util.*;

/** Explicit formal route. No LLM-generated premise is silently treated as a faithful translation. */
public final class FormalReasoningBridge {
    private final ReasoningBackend reasoningBackend;
    private final SemanticExplanationProvider explanationService;
    public FormalReasoningBridge() { this(new ModelExplorerBackend()); }
    public FormalReasoningBridge(ReasoningBackend reasoningBackend) {
        this(reasoningBackend,null);
    }
    public FormalReasoningBridge(ReasoningBackend reasoningBackend,SemanticExplanationProvider explanations) {
        this.explanationService=explanations;
        this.reasoningBackend=Objects.requireNonNull(reasoningBackend);
    }
    private IntegratedQuestionCycle newCycle() {
        return new IntegratedQuestionCycle(ModelExplorer.Budget.small(),reasoningBackend,explanationService);
    }
    private static final String COMMAND_PREFIX = ":raisonner ";
    public record Receipt(String questionId,String status,boolean proofChecked,String explanation,String representation,
                          boolean investigationProposed,String route,Optional<SemanticExplanation> semanticExplanation) {
        public Receipt { Objects.requireNonNull(semanticExplanation); }
        public Receipt(String questionId,String status,boolean proofChecked,String explanation,String representation,boolean investigationProposed,String route){
            this(questionId,status,proofChecked,explanation,representation,investigationProposed,route,Optional.empty());
        }
        public Receipt(String questionId,String status,boolean proofChecked,String explanation,String representation,boolean investigationProposed){
            this(questionId,status,proofChecked,explanation,representation,investigationProposed,"EXPLICIT");
        }
    }
    public record Result(String text,Receipt receipt) { }
    public static boolean requested(String input) {
        return input != null && input.startsWith(COMMAND_PREFIX);
    }
    public Optional<Result> tryAutomatic(String input){
        return AutomaticFormalRouter.recognize(input).map(translation->{
            var cycle=newCycle();
            // Consume the serialized representation using the existing FCI/proof service.
            var answer=new StructuredReasoningService(cycle).askJson(translation.json());
            return finish(answer,translation.json(),"AUTOMATIC_COPULAR");
        });
    }
    public Result evaluate(String input){
        if (!requested(input)) throw new IllegalArgumentException("Commande :raisonner attendue");
        String body = input.substring(COMMAND_PREFIX.length()).strip();
        if (body.isBlank()) throw new IllegalArgumentException("Énoncé formel requis");
        var cycle=newCycle();StructuredReasoningService.Answer answer;String representation;
        if(body.startsWith("{")){
            answer=new StructuredReasoningService(cycle).askJson(body);representation=body;
        }else{
            var turn=new FrenchStructuredReasoningService(cycle).respond(body);answer=turn.answer();representation=turn.translation().json();
        }
        return finish(answer,representation,"EXPLICIT");
    }
    private Result finish(StructuredReasoningService.Answer answer,String representation,String route){
        boolean checked=answer.checkedProof().map(ProofChecker.Result::valid).orElse(false);
        var receipt=new Receipt(answer.questionId(),answer.status().name(),checked,answer.explanation(),representation,answer.investigation().isPresent(),route,answer.semanticExplanation());
        String conclusion=switch(answer.status()){
            case TRUE_CONDITIONALLY->"Oui, sous les prémisses fournies.";
            case FALSE_CONDITIONALLY->"Non, sous les prémisses fournies.";
            case UNDETERMINED->"Les prémisses fournies ne permettent pas de trancher.";
            case INCONSISTENT->"Les prémisses sont contradictoires ; aucune conclusion ordinaire n'est validée.";
            default->"Le noyau n'a pas établi de conclusion : "+answer.status()+".";
        };
        return new Result(conclusion+"\nMode formel "+route+" : seules les prémisses de ce message sont utilisées ; aucune vérité empirique certifiée.\n"+answer.describe(),receipt);
    }
}
