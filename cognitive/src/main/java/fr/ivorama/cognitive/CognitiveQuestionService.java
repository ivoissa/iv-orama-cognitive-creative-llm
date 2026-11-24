package fr.ivorama.cognitive;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Première interface de questions contrôlées. Respecte FCI-002 : Q ouvre la faille,
 * une réponse justifiée la répare. Toutes les vérités sont relatives aux prémisses. */
public final class CognitiveQuestionService {
    public enum Result { YES, NO, UNKNOWN, INCONSISTENT, RESOURCE_LIMIT }

    public record Answer(Result result, InterrogativeUniverse.Phase phase, String text,
                         String justification, boolean proofChecked) {}

    private final KnowledgeBase knowledge = new KnowledgeBase();
    private final ModelExplorer.Budget budget;
    private int nextPremise = 1, nextQuestion = 1;

    public CognitiveQuestionService(ModelExplorer.Budget budget) {
        this.budget = Objects.requireNonNull(budget);
    }
    public CognitiveQuestionService() { this(ModelExplorer.Budget.small()); }

    /** Premisse déclarée, source non vérifiée empiriquement. */
    public String teach(String expression, String source) {
        return teach(PropositionalInput.parse(expression), source);
    }
    public String teach(Formula formula, String source) {
        Objects.requireNonNull(formula);
        String id = "P" + nextPremise;
        knowledge.addPremise(id, formula, source, KnowledgeBase.Kind.ASSERTION);
        nextPremise++;
        return id;
    }

    /** Observation déclarée par une personne : provenance obligatoire, sans garantie empirique. */
    public String teachObservation(Formula formula, String source) {
        Objects.requireNonNull(formula, "formula");
        String id = "P" + nextPremise;
        knowledge.addPremise(id, formula, source, KnowledgeBase.Kind.OBSERVATION);
        nextPremise++;
        return id;
    }

    public void retract(String id) { knowledge.retractPremise(id); }
    public void replace(String id, String expression, String source) {
        knowledge.replacePremise(id,PropositionalInput.parse(expression),source,KnowledgeBase.Kind.ASSERTION);
    }
    public List<KnowledgeBase.Revision> revisions() { return knowledge.revisions(); }
    public long knowledgeVersion() { return knowledge.version(); }
    public Set<Formula> activePremises() { return knowledge.activePremises(); }
    public List<KnowledgeBase.Premise> premises() { return knowledge.premisesHistory(); }

    public Answer ask(String expression) {
        return ask(PropositionalInput.parse(expression));
    }

    /** Entrée formelle directe, sans aller-retour par un analyseur de texte. */
    public Answer ask(Formula question) {
        Objects.requireNonNull(question);
        Set<Formula> context = knowledge.activePremises();
        var asked = InterrogativeUniverse.stable(context)
                .ask(new InterrogativeUniverse.QuestionAct("Q" + nextQuestion++,
                        InterrogativeUniverse.Origin.HUMAN, QuestionGap.Question.of(question)));
        // The initial question act always creates the gap; a stored answer alone does not repair it.
        if (asked.snapshot(budget).phase() != InterrogativeUniverse.Phase.OPEN)
            throw new IllegalStateException("Interrogative opening invariant violated");
        ModelExplorer.Analysis analysed = ModelExplorer.analyze(context, question, budget);
        return switch (analysed.status()) {
            case PROVEN_TRUE -> decided(asked, context, question, true);
            case PROVEN_FALSE -> decided(asked, context, question, false);
            case UNDETERMINED -> new Answer(Result.UNKNOWN, InterrogativeUniverse.Phase.OPEN,
                    "Je ne peux pas déterminer la réponse avec les prémisses actuelles.",
                    "Deux interprétations compatibles donnent des réponses opposées ; la faille demeure ouverte.", false);
            case INCONSISTENT -> new Answer(Result.INCONSISTENT, InterrogativeUniverse.Phase.OPEN,
                    "Je ne peux pas répondre : les prémisses sont contradictoires.",
                    "Une contradiction ne permet pas de restaurer une stabilité justifiée.", false);
            case RESOURCE_LIMIT -> new Answer(Result.RESOURCE_LIMIT, InterrogativeUniverse.Phase.OPEN,
                    "Je ne peux pas conclure dans le budget de calcul autorisé.",
                    "Recherche bornée : absence de résultat ne signifie ni oui ni non.", false);
        };
    }

    private Answer decided(InterrogativeUniverse asked, Set<Formula> context,
                           Formula question, boolean yes) {
        var answered = asked.respond(new InterrogativeUniverse.AnswerAct(Map.of("question", yes),
                QuestionGap.Response.empty(), "détermination logique à partir des prémisses"));
        var snapshot = answered.snapshot(budget);
        if (snapshot.phase() != InterrogativeUniverse.Phase.RESTORED_CONDITIONALLY)
            throw new IllegalStateException("A justified answer did not close the question");
        Formula proved = yes ? question : Formula.not(question);
        Optional<Proof> proof = ProofSearch.find(context, proved, 200);
        boolean proofChecked = proof.isPresent() && ProofChecker.verify(proof.get(), context).valid();
        String justification = proofChecked
                ? "Preuve déductive contrôlée ; conclusion conditionnelle aux prémisses admises."
                : "Conséquence contrôlée par exploration exhaustive des modèles finis ; "
                    + "aucune preuve déductive n'a été construite par le chercheur actuel.";
        return new Answer(yes ? Result.YES : Result.NO,
                snapshot.phase(), yes ? "Oui, dans le cadre des prémisses admises."
                        : "Non, dans le cadre des prémisses admises.", justification, proofChecked);
    }
}
