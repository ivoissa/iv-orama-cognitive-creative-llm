package fr.ivorama.cognitive;

import java.util.*;
import java.time.Instant;

/**
 * IVOC-FND-004: cycle interrogatif unifié en mémoire du processus.
 * Q crée l'obligation; exploration, RDO et justification orientent l'action;
 * seule une réponse explicite conditionnellement établie ferme cette obligation.
 * Aucun paramètre entraîné ni validation empirique d'une déclaration utilisateur.
 * Non thread-safe, non persistant sur disque, limité au propositionnel fini.
 */
public final class IntegratedQuestionCycle {
    public enum Status { OPEN, NEED_INFORMATION, RESTORED_CONDITIONALLY, RESTORED_FORMALLY, RESTORED_JUSTIFIED, INCONSISTENT, RESOURCE_LIMIT }
    /** A formal proof is not empirical validation of its premises. */
    public enum Support { NONE, DIRECT_UNVERIFIED, PROOF_CONDITIONAL, MODEL_ONLY_CONDITIONAL, FORMAL_THEOREM, INDEPENDENTLY_VERIFIED }
    public record Report(String id, Formula question, Status status, Optional<Boolean> answer,
                         Optional<Formula> requestedObservation, boolean proofChecked, Support support,
                         OptionalDouble questionGainBits, boolean reopened,
                         long knowledgeVersion, String explanation, Optional<ProvenanceAudit.Certificate> certificate) {
        public Report {
            Objects.requireNonNull(id); Objects.requireNonNull(question);
            Objects.requireNonNull(status); Objects.requireNonNull(answer); Objects.requireNonNull(support);
            Objects.requireNonNull(requestedObservation); Objects.requireNonNull(questionGainBits);
            Objects.requireNonNull(explanation); Objects.requireNonNull(certificate);
        }
    }
    public record Event(long number, String questionId, String operation, String detail) {}
    public record PartitionReport(String id, QuestionPartition.Status status,
                                  Optional<String> answerCell, boolean reopened,
                                  long knowledgeVersion, Optional<ProvenanceAudit.Certificate> certificate) {}
    private static final class PartitionSession {
        final String id;
        final QuestionPartition.Definition definition;
        boolean everAnswered;
        PartitionSession(String id, QuestionPartition.Definition definition) {
            this.id = id; this.definition = definition;
        }
    }
    private final LinkedHashMap<String, PartitionSession> partitionSessions = new LinkedHashMap<>();
    /** Question posée, mais dont la formule n'est pas encore déterminée lexicalement. */
    public record UnresolvedQuestion(String id, String expression, String ambiguousWord,
                                     List<String> choices, Set<Formula> initialPremises) {
        public UnresolvedQuestion {
            choices=List.copyOf(choices);
            initialPremises=Set.copyOf(initialPremises);
        }
    }
    public record WithdrawnQuestion(String id, String expression, String reason) {}
    private final LinkedHashMap<String,WithdrawnQuestion> withdrawnQuestions = new LinkedHashMap<>();
    public List<WithdrawnQuestion> withdrawnQuestions() { return List.copyOf(withdrawnQuestions.values()); }

    /** Why and how-to-know are distinct acts, never synthetic yes/no questions. */
    public enum ExplanationStatus { PROVEN_FORMALLY, PROVEN_CONDITIONALLY,
        DIRECT_ASSERTION_ONLY, NO_CHECKED_PROOF, NOT_ESTABLISHED, INCONSISTENT, RESOURCE_LIMIT }
    public record ExplanationQuestion(String id,String expression, Formula goal,Set<Formula> initialPremises) {
        public ExplanationQuestion { initialPremises=Set.copyOf(initialPremises); }
    }
    public record ExplanationReport(String id, Formula goal, ExplanationStatus status,
                                    Optional<ProvenanceAudit.Certificate> certificate, long knowledgeVersion) {}
    private final LinkedHashMap<String,ExplanationQuestion> explanationQuestions = new LinkedHashMap<>();
    public record InvestigationQuestion(String id,String expression,Formula goal,Set<Formula> initialPremises) {
        public InvestigationQuestion { initialPremises=Set.copyOf(initialPremises); }
    }
    public enum InvestigationStatus { ALREADY_DETERMINED, PLAN_AVAILABLE, NO_PLAN, INCONSISTENT, RESOURCE_LIMIT }
    public record InvestigationReport(String id,Formula goal,InvestigationStatus status,
            List<RdoPlanner.Candidate> sequence, String limitation, long knowledgeVersion) {
        public InvestigationReport { sequence=List.copyOf(sequence); }
    }
    private final LinkedHashMap<String,InvestigationQuestion> investigationQuestions=new LinkedHashMap<>();
    public ExplanationReport askExplanation(String expression,Formula goal) {
        Objects.requireNonNull(goal);
        String id="Q"+nextQuestion++;
        explanationQuestions.put(id,new ExplanationQuestion(id,expression,goal,service.activePremises()));
        log(id,"QUESTION_POSED","EXPLANATION : "+expression);
        return explanationQuestion(id);
    }
    public ExplanationReport explanationQuestion(String id) {
        var q=explanationQuestions.get(id);
        if(q==null) throw new IllegalArgumentException("Question explicative inconnue : "+id);
        var active=service.activePremises();
        var evaluation=reasoningBackend.analyze(active,q.goal(),budget);
        ExplanationStatus status;
        Optional<ProvenanceAudit.Certificate> cert=Optional.empty();
        if(evaluation.status()==ModelExplorer.Status.INCONSISTENT) status=ExplanationStatus.INCONSISTENT;
        else if(evaluation.status()==ModelExplorer.Status.RESOURCE_LIMIT) status=ExplanationStatus.RESOURCE_LIMIT;
        else if(evaluation.status()!=ModelExplorer.Status.PROVEN_TRUE) status=ExplanationStatus.NOT_ESTABLISHED;
        else {
            var candidate=ProvenanceAudit.inspect(q.goal(),active,service.premises(),
                    service.knowledgeVersion(),PROOF_SEARCH_BUDGET,budget);
            cert=Optional.of(candidate);
            status=switch(candidate.basis()) {
                case FORMAL_THEOREM -> ExplanationStatus.PROVEN_FORMALLY;
                case PROOF_CONDITIONAL -> ExplanationStatus.PROVEN_CONDITIONALLY;
                case DIRECT_UNVERIFIED -> ExplanationStatus.DIRECT_ASSERTION_ONLY;
                case MODEL_ONLY_CONDITIONAL,NOT_ESTABLISHED -> ExplanationStatus.NO_CHECKED_PROOF;
            };
            if((status==ExplanationStatus.PROVEN_FORMALLY || status==ExplanationStatus.PROVEN_CONDITIONALLY)
                    && (!candidate.proofChecked() || candidate.formalProof().isEmpty()))
                status=ExplanationStatus.NO_CHECKED_PROOF;
        }
        return new ExplanationReport(id,q.goal(),status,cert,service.knowledgeVersion());
    }
    public InvestigationReport askInvestigation(String expression,Formula goal) {
        Objects.requireNonNull(goal);
        String id="Q"+nextQuestion++;
        investigationQuestions.put(id,new InvestigationQuestion(id,expression,goal,service.activePremises()));
        log(id,"QUESTION_POSED","INVESTIGATION : "+expression);
        return investigationQuestion(id);
    }
    public InvestigationReport investigationQuestion(String id) {
        var q=investigationQuestions.get(id);
        if(q==null) throw new IllegalArgumentException("Question d'investigation inconnue : "+id);
        var candidates=List.copyOf(observableRegistry.values());
        var plan=RdoPlanner.propose(service.activePremises(),q.goal(),candidates,budget);
        InvestigationStatus status=switch(plan.analysis().status()) {
            case INCONSISTENT -> InvestigationStatus.INCONSISTENT;
            case RESOURCE_LIMIT -> InvestigationStatus.RESOURCE_LIMIT;
            case PROVEN_TRUE, PROVEN_FALSE -> InvestigationStatus.ALREADY_DETERMINED;
            case UNDETERMINED -> plan.choice().isPresent() ? InvestigationStatus.PLAN_AVAILABLE : InvestigationStatus.NO_PLAN;
        };
        return new InvestigationReport(id,q.goal(),status,plan.plannedSequence(),plan.explanation(),service.knowledgeVersion());
    }
    /** Uninterpreted Q is still an interrogative act: zero invented Formula, zero facts. */
    String openOwnedUninterpreted(String expression,String reason,Object owner) {
        Objects.requireNonNull(owner);
        if(expression==null || expression.isBlank() || reason==null || reason.isBlank())
            throw new IllegalArgumentException("Question et motif requis");
        String id="Q"+nextQuestion++;
        unresolvedQuestions.put(id,new UnresolvedQuestion(id,expression,reason,List.of(),service.activePremises()));
        unresolvedOwners.put(id,owner);
        log(id,"QUESTION_UNINTERPRETED",reason);
        return id;
    }

    private final LinkedHashMap<String,UnresolvedQuestion> unresolvedQuestions = new LinkedHashMap<>();
    /** Opaque owner: a dialogue-created Q cannot be bypassed through cycle(). */
    private final Map<String,Object> unresolvedOwners = new HashMap<>();
    /** Temporal query: typed RSI, never smuggled in as an unverified Formula. */
    public record TemporalQuestion(String id, String expression,
            SemanticIntermediateRepresentation frame,
            long initialEventVersion, OptionalLong asOfJournalVersion, Set<Formula> initialPremises) {
        public TemporalQuestion { initialPremises=Set.copyOf(initialPremises); Objects.requireNonNull(asOfJournalVersion); }
    }
    private final Map<String,TemporalQuestion> temporalQuestions=new LinkedHashMap<>();
    private final Map<String,TemporalEventLedger.Finding> temporalFindings=new HashMap<>();
    private final Map<String,Long> temporalObservedVersions=new HashMap<>();
    /** Status transitions are journaled, but re-reading an unchanged version is idempotent. */
    void recordTemporalFinding(String id,TemporalEventLedger.Finding finding,long ledgerVersion) {
        if(!temporalQuestions.containsKey(id)) throw new IllegalArgumentException("Question temporelle inconnue");
        if(Objects.equals(temporalObservedVersions.get(id),ledgerVersion)) return;
        var previous=temporalFindings.put(id,finding);
        temporalObservedVersions.put(id,ledgerVersion);
        boolean nowKnown=finding!=TemporalEventLedger.Finding.NOT_ESTABLISHED
                && finding!=TemporalEventLedger.Finding.CAUSAL_CLAIM_ONLY;
        boolean previouslyKnown=previous!=null && previous!=TemporalEventLedger.Finding.NOT_ESTABLISHED
                && previous!=TemporalEventLedger.Finding.CAUSAL_CLAIM_ONLY;
        if(previous==null) log(id,nowKnown?"ANSWER_GIVEN":"QUESTION_PENDING",finding.name());
        else if(previouslyKnown && !nowKnown) log(id,"QUESTION_REOPENED",finding.name());
        else if(previouslyKnown && nowKnown && previous!=finding) log(id,"ANSWER_REVISED",finding.name());
        else if(!previouslyKnown && nowKnown) log(id,"ANSWER_GIVEN",finding.name());
    }
    String openOwnedTemporal(String expression,
            SemanticIntermediateRepresentation frame,
            long eventVersion,Object owner) {
        return openOwnedTemporal(expression,frame,eventVersion,OptionalLong.empty(),owner);
    }
    String openOwnedTemporal(String expression,
            SemanticIntermediateRepresentation frame,
            long eventVersion,OptionalLong asOfVersion,Object owner) {
        Objects.requireNonNull(owner); Objects.requireNonNull(frame);
        Objects.requireNonNull(asOfVersion);
        if(!(frame instanceof SemanticIntermediateRepresentation.TemporalQuestionFrame)
                && !(frame instanceof SemanticIntermediateRepresentation.CausalQuestionFrame))
            throw new IllegalArgumentException("RSI interrogative requise");
        if(expression==null || expression.isBlank()) throw new IllegalArgumentException("Expression temporelle vide");
        String id="Q"+nextQuestion++;
        temporalQuestions.put(id,new TemporalQuestion(id,expression,frame,eventVersion,
                asOfVersion,service.activePremises()));
        log(id,"QUESTION_POSED","TEMPORAL_RSI : "+frame+" ; version événements="+eventVersion
                +" ; vue historique="+asOfVersion);
        return id;
    }
    public TemporalQuestion temporalQuestion(String id) {
        var q=temporalQuestions.get(id);
        if(q==null) throw new IllegalArgumentException("Question temporelle inconnue : "+id);
        return q;
    }
    void withdrawTemporal(String id,String cause) {
        var q=temporalQuestions.remove(id);
        temporalFindings.remove(id);
        temporalObservedVersions.remove(id);
        if(q==null) throw new IllegalArgumentException("Question temporelle inconnue : "+id);
        withdrawnQuestions.put(id,new WithdrawnQuestion(id,q.expression(),cause));
        log(id,"QUESTION_WITHDRAWN",cause);
    }
    public enum ActState { INTERPRETED, NEEDS_CLARIFICATION, UNINTERPRETED, EXPLANATION, INVESTIGATION, TEMPORAL, WITHDRAWN, PARTITION, ONTOLOGICAL, ORIENTING }
    public record QuestionActView(String id, ActState state, String expression, Optional<Formula> formula) {}
    /** Non-propositionnelle : aucun glissement artificiel vers Formula/ProofChecker. */
    public record OntologyQuestionAct(String id, String expression, String state, long graphVersion) {}
    private final LinkedHashMap<String, OntologyQuestionAct> ontologyQuestions = new LinkedHashMap<>();
    private final Map<String,SemanticIntermediateRepresentation.RelationFrame> ontologyFrames=new LinkedHashMap<>();
    public void interpretOntologyQuestion(String id, SemanticIntermediateRepresentation.RelationFrame frame) {
        Objects.requireNonNull(frame);
        if (!ontologyQuestions.containsKey(id)) throw new IllegalArgumentException("Question ontologique inconnue: " + id);
        SemanticIntermediateRepresentation.RelationFrame prior=ontologyFrames.putIfAbsent(id,frame);
        if (prior!=null && !prior.equals(frame)) throw new IllegalStateException("Une RSI ne peut pas être changée silencieusement");
        if(prior==null)log(id,"ONTOLOGY_INTERPRETED",frame.toString());
    }
    public void recordOntologyClarification(String id,String expression) {
        ontologyQuestion(id);
        if(expression==null || expression.isBlank())throw new IllegalArgumentException("Clarification vide");
        log(id,"ONTOLOGY_CLARIFICATION",expression);
    }
    public Optional<SemanticIntermediateRepresentation.RelationFrame> ontologyRepresentation(String id) {
        if(!ontologyQuestions.containsKey(id))throw new IllegalArgumentException("Question ontologique inconnue: " + id);
        return Optional.ofNullable(ontologyFrames.get(id));
    }
    private final Map<String,FrenchQuestionAnalyzer.Analysis> questionAnalyses=new LinkedHashMap<>();
    public void recordQuestionAnalysis(String id,FrenchQuestionAnalyzer.Analysis analysis){
        ontologyQuestion(id);Objects.requireNonNull(analysis);
        questionAnalyses.put(id,analysis);log(id,"QUESTION_ANALYSIS_CANDIDATE",analysis.toString());
    }
    public Optional<FrenchQuestionAnalyzer.Analysis> questionAnalysis(String id){
        ontologyQuestion(id);return Optional.ofNullable(questionAnalyses.get(id));
    }
    private final Map<String,SemanticIntermediateRepresentation.LogicProblemFrame> structuredFrames=new LinkedHashMap<>();
    public void recordStructuredRepresentation(String id,SemanticIntermediateRepresentation.LogicProblemFrame frame){
        ontologyQuestion(id);Objects.requireNonNull(frame);
        if(structuredFrames.putIfAbsent(id,frame)!=null)throw new IllegalArgumentException("RSI structurée déjà enregistrée");
        log(id,"STRUCTURED_RSI_CANDIDATE",frame.toString());
    }
    public Optional<SemanticIntermediateRepresentation.LogicProblemFrame> structuredRepresentation(String id){
        ontologyQuestion(id);return Optional.ofNullable(structuredFrames.get(id));
    }
    public void recordStructuredResearch(String id,String detail){
        ontologyQuestion(id);log(id,"STRUCTURED_WEB",detail);
    }
    public void recordStructuredFinding(String id,String finding){
        var previous=ontologyQuestion(id);
        ontologyQuestions.put(id,new OntologyQuestionAct(id,previous.expression(),finding,-1));
        log(id,"STRUCTURED_ASSESSED",finding+" ; prémisses locales, aucune validation empirique");
    }
    public String openGeneralQuestion(String question){return openRegisteredQuestion(question,"GENERAL");}
    public String openOntologyQuestion(String question) {return openRegisteredQuestion(question,"ONTOLOGICAL");}
    private String openRegisteredQuestion(String question,String kind) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question obligatoire");
        String id = "Q" + nextQuestion++;
        ontologyQuestions.put(id, new OntologyQuestionAct(id, question, "OPEN", -1));
        log(id, "QUESTION_POSED", kind+" : " + question);
        return id;
    }
    /** Trace une évaluation sans prétendre fermer formellement la FCI. */
    public void recordOntologyFinding(String id, String state, long graphVersion) {
        OntologyQuestionAct old = ontologyQuestions.get(id);
        if (old == null) throw new IllegalArgumentException("Question ontologique inconnue: " + id);
        if (state == null || state.isBlank() || graphVersion < 0) throw new IllegalArgumentException("Évaluation invalide");
        if (old.state().equals(state) && old.graphVersion() == graphVersion) return;
        ontologyQuestions.put(id, new OntologyQuestionAct(id, old.expression(), state, graphVersion));
        log(id, "ONTOLOGY_ASSESSED", state + " @graphe=" + graphVersion);
    }
    public OntologyQuestionAct ontologyQuestion(String id) {
        OntologyQuestionAct q = ontologyQuestions.get(id);
        if (q == null) throw new IllegalArgumentException("Question ontologique inconnue: " + id);
        return q;
    }
    public List<OntologyQuestionAct> ontologyQuestions() { return List.copyOf(ontologyQuestions.values()); }
    /** Exhaustive, typed view: never invent a Formula for a pending question. */
    public List<QuestionActView> questionActs() {
        List<QuestionActView> acts=new ArrayList<>();
        for(var s:sessions.values()) acts.add(new QuestionActView(s.id,ActState.INTERPRETED,s.question.toString(),Optional.of(s.question)));
        for(var u:unresolvedQuestions.values()) acts.add(new QuestionActView(u.id(),u.choices().isEmpty() ? ActState.UNINTERPRETED : ActState.NEEDS_CLARIFICATION,u.expression(),Optional.empty()));
        for(var q:explanationQuestions.values()) acts.add(new QuestionActView(q.id(),ActState.EXPLANATION,q.expression(),Optional.of(q.goal())));
        for(var q:investigationQuestions.values()) acts.add(new QuestionActView(q.id(),ActState.INVESTIGATION,q.expression(),Optional.of(q.goal())));
        for(var q:temporalQuestions.values()) acts.add(new QuestionActView(q.id(),ActState.TEMPORAL,q.expression(),Optional.empty()));
        for(var q:ontologyQuestions.values()) acts.add(new QuestionActView(q.id(),(structuredFrames.containsKey(q.id()) || questionAnalyses.containsKey(q.id())) && !ontologyFrames.containsKey(q.id())?ActState.ORIENTING:ActState.ONTOLOGICAL,q.expression(),Optional.empty()));
        for(var w:withdrawnQuestions.values()) acts.add(new QuestionActView(w.id(),ActState.WITHDRAWN,w.expression(),Optional.empty()));
        for(var q:partitionSessions.values()) acts.add(new QuestionActView(q.id,ActState.PARTITION,"partition",Optional.empty()));
        acts.sort(Comparator.comparingInt(v->Integer.parseInt(v.id().substring(1))));
        return List.copyOf(acts);
    }
    public QuestionActView questionAct(String id) {
        return questionActs().stream().filter(q->q.id().equals(id)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Acte interrogatif inconnu : "+id));
    }
    private void checkOwner(String id,Object provided) {
        Object owner=unresolvedOwners.get(id);
        if(owner!=provided) throw new IllegalStateException("Obligation gouvernée par un dialogue : utiliser precise/annule");
    }
    public List<UnresolvedQuestion> unresolvedQuestions() {
        return List.copyOf(unresolvedQuestions.values());
    }
    public int questionActCount() {
        return sessions.size()+partitionSessions.size()+unresolvedQuestions.size()+withdrawnQuestions.size()
                +explanationQuestions.size()+investigationQuestions.size()+temporalQuestions.size()+ontologyQuestions.size();
    }
    /** Un acte Q => un identifiant ; aucune formule n'est inventée avant clarification. */
    public String openUnresolved(String expression, String ambiguousWord, List<String> choices) {
        return openUnresolvedInternal(expression,ambiguousWord,choices,null);
    }
    String openOwnedUnresolved(String expression,String word,List<String> choices,Object owner) {
        return openUnresolvedInternal(expression,word,choices,Objects.requireNonNull(owner));
    }
    private String openUnresolvedInternal(String expression, String ambiguousWord, List<String> choices,Object owner) {
        if(expression==null || expression.isBlank() || choices==null || choices.size()<2)
            throw new IllegalArgumentException("Question ambiguë et sens candidats nécessaires");
        String id="Q"+nextQuestion++;
        unresolvedQuestions.put(id,new UnresolvedQuestion(id,expression,ambiguousWord,choices,
                service.activePremises()));
        if(owner!=null) unresolvedOwners.put(id,owner);
        log(id,"QUESTION_POSED","interprétation non résolue");
        return id;
    }
    /** Réaffine la même obligation lorsque plusieurs mots restent ambigus. */
    public void updateUnresolved(String id, String ambiguousWord, List<String> choices) {
        updateUnresolvedInternal(id,ambiguousWord,choices,null);
    }
    void updateOwnedUnresolved(String id,String word,List<String> choices,Object owner) {
        updateUnresolvedInternal(id,word,choices,Objects.requireNonNull(owner));
    }
    private void updateUnresolvedInternal(String id, String ambiguousWord, List<String> choices,Object owner) {
        var old=unresolvedQuestions.get(id);
        if(old==null) throw new IllegalArgumentException("Question non ambiguë ou inconnue : "+id);
        checkOwner(id,owner);
        unresolvedQuestions.put(id,new UnresolvedQuestion(id,old.expression(),ambiguousWord,choices,
                old.initialPremises()));
        log(id,"CLARIFICATION_STILL_NEEDED",ambiguousWord);
    }
    /** Retire une obligation non interprétée, sans nier la question ni effacer l'acte historique. */
    public void withdrawUnresolved(String id, String reason) {
        withdrawUnresolvedInternal(id,reason,null);
    }
    void withdrawOwnedUnresolved(String id,String reason,Object owner) {
        withdrawUnresolvedInternal(id,reason,Objects.requireNonNull(owner));
    }
    private void withdrawUnresolvedInternal(String id,String reason,Object owner) {
        var q=unresolvedQuestions.get(id);
        if(q==null) throw new IllegalArgumentException("Question non interprétée inconnue : "+id);
        checkOwner(id,owner);
        String cause=Objects.requireNonNull(reason).strip();
        if(cause.isEmpty()) throw new IllegalArgumentException("Cause de retrait obligatoire");
        unresolvedQuestions.remove(id);
        unresolvedOwners.remove(id);
        withdrawnQuestions.put(id,new WithdrawnQuestion(id,q.expression(),cause));
        log(id,"CLARIFICATION_REFUSED",cause);
    }
    /** Préciser Q ne crée PAS un second acte Q. */
    public Report resolveUnresolved(String id, Formula formula) {
        return resolveUnresolvedInternal(id,formula,null);
    }
    Report resolveOwnedUnresolved(String id,Formula formula,Object owner) {
        return resolveUnresolvedInternal(id,formula,Objects.requireNonNull(owner));
    }
    private Report resolveUnresolvedInternal(String id, Formula formula,Object owner) {
        Objects.requireNonNull(formula);
        var unresolved=unresolvedQuestions.get(id);
        if(unresolved==null) throw new IllegalArgumentException("Question non ambiguë ou inconnue : "+id);
        checkOwner(id,owner);
        Session session=new Session(id,formula,unresolved.initialPremises());
        sessions.put(id,session);
        try {
            Report report=refresh(session);
            unresolvedQuestions.remove(id);
            unresolvedOwners.remove(id);
            log(id,"QUESTION_CLARIFIED",formula.toString());
            return report;
        } catch(RuntimeException exception) {
            sessions.remove(id);
            // La question demeure en attente ; l'appelant peut réessayer.
            throw exception;
        }
    }


    private static final int PROOF_SEARCH_BUDGET = 200;
    private final CognitiveQuestionService service;
    private final ModelExplorer.Budget budget;
    private final ReasoningBackend reasoningBackend;
    private final SemanticExplanationProvider explanationService;
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private final LinkedHashMap<Formula, RdoPlanner.Candidate> observableRegistry = new LinkedHashMap<>();
    private final List<Event> events = new ArrayList<>();
    private long catalogVersion;
    private final ObservationLedger observationLedger = new ObservationLedger();
    private final Map<String,String> admittedReadingPremises = new LinkedHashMap<>();
    private int nextQuestion = 1;

    private static final class Session {
        final String id;
        final Formula question;
        final Set<Formula> initialPremises;
        final InterrogativeUniverse.QuestionAct act;
        Report report;
        long lastKnowledge = -1, lastCatalog = -1;
        boolean hasEverRestored;
        final Set<Formula> declinedObservations = new LinkedHashSet<>();
        Session(String id, Formula question, Set<Formula> initialPremises) {
            this.id = id;
            this.question = question;
            this.initialPremises = Set.copyOf(initialPremises);
            this.act = new InterrogativeUniverse.QuestionAct(id,
                    InterrogativeUniverse.Origin.HUMAN, QuestionGap.Question.of(question));
        }
    }

    public IntegratedQuestionCycle(ModelExplorer.Budget budget) {
        this(budget, new ModelExplorerBackend());
    }
    /** Optional backend is independently compared; FCI/RDO and proofs keep their reference semantics. */
    public IntegratedQuestionCycle(ModelExplorer.Budget budget, ReasoningBackend candidate) {
        this(budget,candidate,null);
    }
    /** Explicit explanation enrichment; never changes the answering backend or proof rules. */
    public IntegratedQuestionCycle(ModelExplorer.Budget budget, ReasoningBackend candidate, SemanticExplanationProvider explanations) {
        this.explanationService=explanations;
        this.reasoningBackend = Objects.requireNonNull(candidate) instanceof ModelExplorerBackend ? candidate : new CheckedReasoningBackend(candidate);
        this.budget = Objects.requireNonNull(budget);
        this.service = new CognitiveQuestionService(budget);
    }
    public IntegratedQuestionCycle() { this(ModelExplorer.Budget.small()); }

    ModelExplorer.Analysis assessSemantics(Collection<Formula> premises, Formula question, ModelExplorer.Budget assessmentBudget) {
        return reasoningBackend.analyze(premises,question,assessmentBudget);
    }

    private List<SemanticExplanation.Premise> explanationPremises() {
        return service.premises().stream().filter(KnowledgeBase.Premise::active)
                .map(p->new SemanticExplanation.Premise(p.id(),p.formula(),p.source(),"")).toList();
    }
    /** Read-only current-context diagnostic. Does not ask a synthetic Q or restore any obligation. */
    public Optional<SemanticExplanation> explainCurrent(Formula question) {
        return explanationService==null?Optional.empty():Optional.of(explanationService.explain(explanationPremises(),question));
    }
    /** P2.3a opt-in proof-routing receipt for an already-decided proposition question.
     * Read-only: never changes the question's verdict, FCI phase, or baseline certificate.
     * A receipt is invalidated by any knowledge-version or active-source change.
     */
    public Optional<P23ProofRouting.Receipt> proofRoutingReceipt(
            String questionId, SemanticExplanationProvider provider, P23ProofRouting.Options options) {
        Objects.requireNonNull(provider); Objects.requireNonNull(options);
        Session session = sessions.get(questionId);
        if (session == null) throw new IllegalArgumentException("Unknown propositional question: " + questionId);
        Report current = refresh(session);
        if (current.answer().isEmpty() || current.certificate().isEmpty()) return Optional.empty();
        Formula goal = current.answer().orElseThrow() ? session.question : Formula.not(session.question);
        long version = service.knowledgeVersion();
        // The cycle and the proof route operate over exactly the same active revision.
        var active = explanationPremises();
        P23ProofRouting.Receipt receipt = P23ProofRouting.inspect(provider,active,goal,version,
                current.certificate().orElseThrow(),options);
        if (!receipt.isCurrent(service.knowledgeVersion(),explanationPremises(),goal))
            throw new IllegalStateException("Premises changed during proof routing");
        return Optional.of(receipt);
    }

    /** Check whether a previously returned routing receipt still describes this live question. */
    public boolean isProofRoutingReceiptCurrent(String questionId, P23ProofRouting.Receipt receipt) {
        Objects.requireNonNull(receipt);
        Session session = sessions.get(questionId);
        if (session == null) return false;
        Report now = refresh(session);
        if (now.answer().isEmpty()) return false;
        Formula goal = now.answer().orElseThrow() ? session.question : Formula.not(session.question);
        return now.certificate().isPresent() && now.certificate().orElseThrow().version() == receipt.knowledgeVersion()
                && receipt.isCurrent(service.knowledgeVersion(),explanationPremises(),goal);
    }

    public boolean isExplanationCurrent(Formula question,SemanticExplanation explanation) {
        return explanationService!=null && explanationService.matches(explanation,explanationPremises(),question);
    }
    boolean explanationsEnabled(){return explanationService!=null;}
    Optional<SemanticExplanation> explainDeclared(List<SemanticExplanation.Premise> premises,Formula question,ModelExplorer.Status expected) {
        if(explanationService==null || expected==ModelExplorer.Status.RESOURCE_LIMIT)return Optional.empty();
        var result=explanationService.explain(premises,question);
        if(result.semanticStatus()!=ModelExplorer.Status.RESOURCE_LIMIT && result.semanticStatus()!=expected) {
            return Optional.of(new SemanticExplanation(SemanticExplanation.Kind.NONE,SemanticExplanation.Status.FAILED,
                    expected,Optional.empty(),Optional.empty(),Optional.empty(),List.of(),false,result.satCalls(),
                    result.oracleAssignments(),result.subsetVisits(),result.contextDigest(),"Explanation/reference semantic disagreement; candidate details rejected"));
        }
        return Optional.of(result);
    }

    public String teach(String expression, String source) {
        return teach(PropositionalInput.parse(expression), source);
    }
    public String teach(Formula formula, String source) {
        String id = service.teach(formula, source);
        log("*", "PREMISE_ADDED", id);
        return id;
    }
    public void retract(String premiseId) {
        service.retract(premiseId);
        log("*", "PREMISE_RETRACTED", premiseId);
        refreshAll();
    }
    public List<KnowledgeBase.Premise> premises() { return service.premises(); }
    public long knowledgeVersion() { return service.knowledgeVersion(); }
    public List<KnowledgeBase.Revision> revisions() { return service.revisions(); }
    public void replace(String premiseId, String expression, String source) {
        service.replace(premiseId, expression, source);
        log("*","PREMISE_REPLACED",premiseId + " : " + expression);
        refreshAll();
    }

    /** Déclaration explicite des propositions OBSERVABLES (pas d'invention de capteurs). */
    public void registerObservable(String expression, int cost) {
        registerObservable(PropositionalInput.parse(expression), cost);
    }
    public void registerObservable(Formula observation, int cost) {
        Objects.requireNonNull(observation);
        RdoPlanner.Candidate candidate = new RdoPlanner.Candidate(observation, cost);
        observableRegistry.put(observation, candidate);
        catalogVersion++;
        log("*", "OBSERVABLE_REGISTERED", observation.toString());
        refreshAll();
    }

    /** Crée toujours f_Q même si la réponse était déjà connue. */
    public Report ask(String expression) {
        return ask(PropositionalInput.parse(expression));
    }
    public Report ask(Formula question) {
        Objects.requireNonNull(question);
        String id = "Q" + nextQuestion++;
        Session session = new Session(id, question, service.activePremises());
        sessions.put(id, session);
        log(id, "QUESTION_POSED", question.toString());
        return refresh(session);
    }

    public Report question(String questionId) {
        Session session = sessions.get(questionId);
        if (session == null) throw new IllegalArgumentException("Question inconnue : " + questionId);
        return refresh(session);
    }

    public List<Report> questions() {
        List<Report> reports = new ArrayList<>();
        for (Session s : sessions.values()) reports.add(refresh(s));
        return List.copyOf(reports);
    }
    public List<Event> journal() { return List.copyOf(events); }

    /** An actual question ACT creates the gap; different partitions mean different questions. */
    public PartitionReport askPartition(List<QuestionPartition.Cell> cells) {
        var definition = new QuestionPartition.Definition(cells);
        if (!QuestionPartition.valid(definition, budget))
            throw new IllegalArgumentException("Invalid answer partition");
        String id = "Q" + nextQuestion++;
        var s = new PartitionSession(id, definition);
        partitionSessions.put(id, s);
        log(id, "QUESTION_POSED", "partition with " + cells.size() + " mutually exclusive cells");
        return partitionQuestion(id);
    }
    public PartitionReport partitionQuestion(String id) {
        var s = partitionSessions.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown partition question " + id);
        var assessment = QuestionPartition.evaluate(s.definition, service.activePremises(), budget);
        boolean reopened = s.everAnswered && assessment.status() != QuestionPartition.Status.DECIDED;
        Optional<ProvenanceAudit.Certificate> certificate = Optional.empty();
        if (assessment.selected().isPresent()) {
            var goal = assessment.selected().orElseThrow().condition();
            certificate = Optional.of(ProvenanceAudit.inspect(goal, service.activePremises(),
                    service.premises(), service.knowledgeVersion(), PROOF_SEARCH_BUDGET, budget));
            if (certificate.orElseThrow().basis() == ProvenanceAudit.Basis.NOT_ESTABLISHED)
                throw new IllegalStateException("Semantic partition decision has no valid support");
            if (!s.everAnswered) log(id,"ANSWER_GIVEN",assessment.selected().orElseThrow().name());
            s.everAnswered = true;
        } else if (reopened) {
            log(id,"QUESTION_REOPENED","previously determined cell is no longer established");
            s.everAnswered = false;
        }
        return new PartitionReport(id, assessment.status(),
                assessment.selected().map(QuestionPartition.Cell::name), reopened,
                service.knowledgeVersion(), certificate);
    }
    public List<PartitionReport> partitionQuestions() {
        List<PartitionReport> reports = new ArrayList<>();
        for (String id : partitionSessions.keySet()) reports.add(partitionQuestion(id));
        return List.copyOf(reports);
    }

    /** A saved answer/certificate is a snapshot, not a timeless attestation. */
    public boolean isCertificateCurrent(String questionId, ProvenanceAudit.Certificate certificate) {
        Objects.requireNonNull(certificate);
        Session session = sessions.get(questionId);
        if (session == null || certificate.version() != service.knowledgeVersion()) return false;
        Report now = refresh(session);
        return now.certificate().isPresent() && now.certificate().orElseThrow().equals(certificate);
    }

    /**
     * Enregistre une observation déclarée par l'utilisateur en réponse à une demande RDO.
     * Une contradiction ou un dépassement du budget est refusé AVANT mutation.
     * L'opération ne prétend pas vérifier empiriquement cette affirmation.
     */
    public Report answerObservation(String questionId, boolean value, String source) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Source requise");
        Session session = sessions.get(questionId);
        if (session == null) throw new IllegalArgumentException("Question inconnue : " + questionId);
        Report before = refresh(session);
        if (before.status() != Status.NEED_INFORMATION || before.requestedObservation().isEmpty())
            throw new IllegalStateException("Aucune observation en attente pour " + questionId);
        Formula observed = before.requestedObservation().orElseThrow();
        Formula claim = value ? observed : Formula.not(observed);
        Set<Formula> trial = new LinkedHashSet<>(service.activePremises());
        trial.add(claim);
        var analysis = reasoningBackend.analyze(trial, session.question, budget);
        if (analysis.status() == ModelExplorer.Status.INCONSISTENT) {
            log(questionId, "OBSERVATION_REJECTED", "Contradiction avec le contexte actif");
            throw new IllegalArgumentException("Observation contradictoire : contexte inchangé");
        }
        if (analysis.status() == ModelExplorer.Status.RESOURCE_LIMIT) {
            log(questionId, "OBSERVATION_REJECTED", "Budget dépassé avant admission");
            throw new IllegalStateException("Observation non admise : vérification hors budget");
        }
        String premiseId = service.teachObservation(claim, source + " (déclaration non vérifiée)");
        log(questionId, "OBSERVATION_DECLARED", premiseId + " : " + claim);
        refreshAll();
        return question(questionId);
    }

    /** A stored, integrity-checked reading still counts only as UNVERIFIED observation. */
    public Report answerRecordedObservation(String questionId, String readingId, Instant at) {
        if (admittedReadingPremises.containsKey(readingId))
            throw new IllegalStateException("Reading already admitted: " + readingId);
        if (observationLedger.check(readingId,at) != ObservationLedger.Status.RECORD_INTEGRITY_OK)
            throw new IllegalStateException("Reading not currently usable: " + readingId);
        var expected = question(questionId);
        if (expected.status()!=Status.NEED_INFORMATION || expected.requestedObservation().isEmpty()
                || !expected.requestedObservation().orElseThrow().equals(
                       observationLedger.reading(readingId).measuredAtom()))
            throw new IllegalArgumentException("Reading does not correspond to the requested observation");
        Set<String> before = new HashSet<>();
        for (var premise : service.premises()) before.add(premise.id());
        var reading = observationLedger.reading(readingId);
        Report response = answerObservation(questionId, reading.rawValue(),
                "raw " + readingId + " source=" + reading.sourceId() +
                " calibration=" + reading.calibrationId() + " digest=" + reading.digest() +
                " (integrite des donnees uniquement, PAS verite empirique)");
        String added = service.premises().stream().map(KnowledgeBase.Premise::id)
                .filter(id->!before.contains(id)).findFirst().orElseThrow();
        admittedReadingPremises.put(readingId,added);
        return response;
    }
    public ObservationLedger.Reading captureObservation(String sourceId, Formula.Atom observedAtom,
            boolean rawValue, String declaredCalibration, Instant at, Instant expiresAt) {
        return observationLedger.capture(sourceId,observedAtom,rawValue,declaredCalibration,at,expiresAt);
    }
    public ObservationLedger.Status observationStatus(String id, Instant at) {
        return observationLedger.check(id,at);
    }
    public List<ObservationLedger.Event> observationJournal() { return observationLedger.journal(); }
    /** Explicit revocation propagates to knowledge and all questioning sessions. */
    public void revokeRecordedObservation(String readingId, String reason) {
        observationLedger.revoke(readingId,reason);
        String premise = admittedReadingPremises.remove(readingId);
        if (premise != null) retract(premise);
        log("*","READING_REVOKED", readingId + " : " + reason);
    }
    /** Explicit clock tick; no unattended scheduler and no implicit expiration claim. */
    public void expireRecordedObservations(Instant now) {
        for (var entry : new ArrayList<>(admittedReadingPremises.entrySet())) {
            if (observationLedger.check(entry.getKey(),now)!=ObservationLedger.Status.RECORD_INTEGRITY_OK) {
                admittedReadingPremises.remove(entry.getKey());
                retract(entry.getValue());
                log("*","READING_EXPIRED_OR_INVALID",entry.getKey());
            }
        }
    }

    /** Human cannot supply an observation; never re-ask the same declined test in this session. */
    public Report declineObservation(String questionId) {
        Session session = sessions.get(questionId);
        if (session == null) throw new IllegalArgumentException("Question inconnue : " + questionId);
        Report before = refresh(session);
        if (before.status() != Status.NEED_INFORMATION || before.requestedObservation().isEmpty())
            throw new IllegalStateException("Aucune observation à décliner pour " + questionId);
        Formula declined = before.requestedObservation().orElseThrow();
        session.declinedObservations.add(declined);
        log(questionId, "OBSERVATION_DECLINED", declined.toString());
        // A change to session-local exploration state also invalidates cache.
        session.lastCatalog = -1;
        return refresh(session);
    }

    private void refreshAll() {
        for (Session s : sessions.values()) refresh(s);
    }

    private Report refresh(Session s) {
        long version = service.knowledgeVersion();
        if (s.report != null && s.lastKnowledge == version && s.lastCatalog == catalogVersion)
            return s.report;

        Set<Formula> active = service.activePremises();
        InterrogativeUniverse opened = InterrogativeUniverse.stable(active).ask(s.act);
        if (opened.snapshot(budget).phase() != InterrogativeUniverse.Phase.OPEN)
            throw new IllegalStateException("FCI-002: un acte Q doit ouvrir la faille");

        // Only the persisted question act creates the interrogative obligation.
        // Mathematical diagnosis must not create hidden/synthetic questions.
        ModelExplorer.Analysis assessed = reasoningBackend.analyze(active, s.question, budget);
        return buildReport(s, opened, assessed, active, version);
    }

    private Report buildReport(Session s, InterrogativeUniverse opened,
                               ModelExplorer.Analysis assessed,
                               Set<Formula> active, long version) {
        Status status;
        Optional<Boolean> answer = Optional.empty();
        Optional<Formula> requested = Optional.empty();
        boolean checked = false;
        Support support = Support.NONE;
        String explanation = assessed.explanation();
        Optional<ProvenanceAudit.Certificate> provenance = Optional.empty();
        if (assessed.status() == ModelExplorer.Status.PROVEN_TRUE
                || assessed.status() == ModelExplorer.Status.PROVEN_FALSE) {
            boolean yes = assessed.status() == ModelExplorer.Status.PROVEN_TRUE;
            var responded = opened.respond(new InterrogativeUniverse.AnswerAct(
                    Map.of("question", yes), QuestionGap.Response.empty(),
                    "réponse explicite sous prémisses déclarées"));
            if (responded.snapshot(budget).phase() != InterrogativeUniverse.Phase.RESTORED_CONDITIONALLY)
                throw new IllegalStateException("Réparation refusée par FCI");
            answer = Optional.of(yes);
            Formula goal = yes ? s.question : Formula.not(s.question);
            ProvenanceAudit.Certificate cert = ProvenanceAudit.inspect(goal, active,
                    service.premises(), version, PROOF_SEARCH_BUDGET, budget);
            provenance = Optional.of(cert);
            checked = cert.proofChecked();
            support = switch (cert.basis()) {
                case FORMAL_THEOREM -> Support.FORMAL_THEOREM;
                case PROOF_CONDITIONAL -> Support.PROOF_CONDITIONAL;
                case DIRECT_UNVERIFIED -> Support.DIRECT_UNVERIFIED;
                case MODEL_ONLY_CONDITIONAL, NOT_ESTABLISHED -> Support.MODEL_ONLY_CONDITIONAL;
            };
            status = cert.hasPremiseFreeFormalCertificate()
                    ? Status.RESTORED_FORMALLY : Status.RESTORED_CONDITIONALLY;
            explanation = cert.limitation() + " Dépendances de preuve syntaxique : "
                    + (cert.proofChecked() ? cert.dependencies().size() + " groupe(s). "
                            : "aucune preuve construite. ")
                    + cert.semanticSupportSummary();
        } else if (assessed.status() == ModelExplorer.Status.INCONSISTENT) {
            status = Status.INCONSISTENT;
        } else if (assessed.status() == ModelExplorer.Status.RESOURCE_LIMIT) {
            status = Status.RESOURCE_LIMIT;
        } else {
            var candidates = observableRegistry.values().stream()
                    .filter(candidate -> !s.declinedObservations.contains(candidate.observation())).toList();
            var plan = RdoPlanner.propose(active, s.question, candidates, budget);
            if (plan.analysis().status() == ModelExplorer.Status.RESOURCE_LIMIT) {
                status = Status.RESOURCE_LIMIT;
                explanation = "RDO : coût de l'exploration supérieur au budget ; aucune réponse inventée.";
            } else if (plan.choice().isPresent()) {
                status = Status.NEED_INFORMATION;
                requested = Optional.of(plan.choice().orElseThrow().candidate().observation());
                explanation = "Information demandée : " + requested.orElseThrow()
                        + ". " + plan.explanation()
                        + ". Aucun capteur exécuté ; déclaration utilisateur non vérifiée.";
            } else {
                status = Status.OPEN;
                explanation = "Faille ouverte ; aucun test pertinent disponible dans le catalogue.";
            }
        }
        OptionalDouble gain = semanticProgress(s, active);
        boolean reopened = s.hasEverRestored && status != Status.RESTORED_CONDITIONALLY && status != Status.RESTORED_FORMALLY && status != Status.RESTORED_JUSTIFIED;
        Report next = new Report(s.id, s.question, status, answer, requested, checked, support,
                gain, reopened, version, explanation, provenance);
        if (s.report == null || s.report.status() != status
                || !s.report.requestedObservation().equals(requested)
                || !s.report.answer().equals(answer)) {
            log(s.id, (status == Status.RESTORED_CONDITIONALLY || status == Status.RESTORED_FORMALLY) ? "ANSWER_GIVEN"
                    : reopened ? "QUESTION_REOPENED" : "QUESTION_PENDING", status.name());
        }
        if (status == Status.RESTORED_CONDITIONALLY || status == Status.RESTORED_FORMALLY || status == Status.RESTORED_JUSTIFIED) s.hasEverRestored = true;
        s.report = next;
        s.lastKnowledge = version;
        s.lastCatalog = catalogVersion;
        return next;
    }

    private OptionalDouble semanticProgress(Session session, Set<Formula> active) {
        if (!active.containsAll(session.initialPremises)) return OptionalDouble.empty();
        List<QuestionGap.Claim> claims = new ArrayList<>();
        for (Formula f : active) if (!session.initialPremises.contains(f))
            claims.add(new QuestionGap.Claim(f, "prémisse admise après Q (non certifiée)",
                    QuestionGap.Admission.ADMITTED));
        var result = QuestionSemantics.assess(session.initialPremises,
                QuestionGap.Question.of(session.question), new QuestionGap.Response(claims), budget);
        return result.status() == QuestionSemantics.Status.EVALUATED
                ? OptionalDouble.of(result.answerGainBits()) : OptionalDouble.empty();
    }

    private void log(String q, String op, String details) {
        events.add(new Event(events.size() + 1L, q, op, details));
    }
}
