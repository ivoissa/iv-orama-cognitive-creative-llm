package fr.ivorama.cognitive;

import java.util.*;

/** Dialogue borné : questions copulaires, clarification et justification conditionnelle. */
public final class OntologyConversation {
    public enum Status { ANSWER, NEEDS_CLARIFICATION, NEEDS_VOCABULARY, DOCUMENTS_ONLY,
        WEB_FAILED, UNDETERMINED, CONTRADICTORY, UNSUPPORTED, NO_CONTEXT, STALE_CONTEXT }
    public enum Intent { CHECK, EXPLAIN }
    public record Turn(String questionId, Status status, String text,
                       List<String> choices, List<WebSource.Hit> documents) {
        public Turn { choices=List.copyOf(choices);documents=List.copyOf(documents); }
        public String describe(){return questionId+" | "+status+"\n"+text;}
    }
    private static final class Session {
        final String id; final Intent intent; final FrenchOntologicalQuestionParser.Surface surface;
        String subject,target;
        List<ConceptNode> choices=List.of();boolean choosingSubject;
        Session(String id,Intent intent,FrenchOntologicalQuestionParser.Surface surface){
            this.id=id;this.intent=intent;this.surface=surface;
        }
    }
    private final OntologicalKnowledgeGraph graph;
    private final IntegratedQuestionCycle cycle;
    private final WebSource.Provider provider;
    private final OntologyWebReasoningService service;
    private final FrenchOntologicalQuestionParser parser=new FrenchOntologicalQuestionParser();
    private Session pending,last;
    private final Map<String,Session> sessions=new LinkedHashMap<>();
    public OntologyConversation(OntologicalKnowledgeGraph graph,IntegratedQuestionCycle cycle,WebSource.Provider provider){
        this.graph=Objects.requireNonNull(graph);this.cycle=Objects.requireNonNull(cycle);
        this.provider=Objects.requireNonNull(provider);this.service=new OntologyWebReasoningService(graph,cycle,provider);
    }
    public Turn respond(String text){return respondRegistered(null,text);}
    /** Adaptateur : conserver l'acte déjà ouvert par l'orientation générale. */
    Turn respondRegistered(String registeredId,String text){
        if(text==null || text.isBlank())throw new IllegalArgumentException("Énoncé vide");
        String normalized=OntologicalKnowledgeGraph.normalized(text).replaceAll("[?.!]+$", "").strip();
        if(normalized.equals("pourquoi") || normalized.equals("et pourquoi")){
            String id=registeredId==null?cycle.openOntologyQuestion(text):registeredId;
            if(last==null || last.subject==null || last.target==null)
                return result(id,Status.NO_CONTEXT,"Aucune relation résolue à expliquer.",List.of(),List.of());
            var s=new Session(id,Intent.EXPLAIN,last.surface);s.subject=last.subject;s.target=last.target;
            sessions.put(id,s);cycle.recordOntologyClarification(id,"Justification de "+last.id);
            return evaluate(s,false);
        }
        // Une réponse courte ne crée pas un nouvel acte interrogatif pendant une clarification.
        if(registeredId==null && pending!=null && !text.contains("?") && !normalized.startsWith("pourquoi ")
                && parser.surface(text).isEmpty())return clarify(text);
        String id=registeredId==null?cycle.openOntologyQuestion(text):registeredId;
        last=null;
        Intent intent=normalized.startsWith("pourquoi ")?Intent.EXPLAIN:Intent.CHECK;
        String relation=intent==Intent.EXPLAIN?text.strip().substring(8).strip():text;
        // « Pourquoi un carré ... » décrit ici une justification logique, jamais une cause physique.
        var surface=parser.surface(relation);
        if(surface.isEmpty())return result(id,Status.UNSUPPORTED,"Forme non prise en charge ; aucune relation inventée.",List.of(),List.of());
        var s=new Session(id,intent,surface.get());sessions.put(id,s);pending=null;
        return resolveTerms(s);
    }
    boolean hasSession(String id){return sessions.containsKey(id);}
    void clearContext(){pending=null;last=null;}
    private Turn clarify(String text){
        Session s=pending;
        List<ConceptNode> matches=s.choices.stream().filter(n->matchesChoice(text,n)).toList();
        if(matches.size()!=1)return result(s.id,Status.NEEDS_CLARIFICATION,
                "Choix non unique ou invalide. Choisir exactement un concept : "+labels(s.choices),labels(s.choices),List.of());
        if(s.choosingSubject)s.subject=matches.get(0).id();else s.target=matches.get(0).id();
        cycle.recordOntologyClarification(s.id,text+" -> "+matches.get(0).id());
        pending=null;return resolveTerms(s);
    }
    private static boolean matchesChoice(String text,ConceptNode node){
        String choice=OntologicalKnowledgeGraph.normalized(text).replaceFirst("^(?:le |la |les |l'|un |une )", "");
        String label=OntologicalKnowledgeGraph.normalized(node.label());
        int at=label.lastIndexOf('(');
        String qualifier=at>=0 && label.endsWith(")")?label.substring(at+1,label.length()-1):"";
        return choice.equals(node.id()) || choice.equals(label) || (!qualifier.isEmpty() && choice.equals(qualifier));
    }
    private Turn resolveTerms(Session s){
        if(s.subject==null){
            var candidates=graph.candidates(s.surface.subject());
            if(candidates.size()>1)return requestChoice(s,candidates,true);
            if(candidates.size()==1)s.subject=candidates.get(0).id();
        }
        if(s.target==null){
            var candidates=graph.candidates(s.surface.target());
            if(candidates.size()>1)return requestChoice(s,candidates,false);
            if(candidates.size()==1)s.target=candidates.get(0).id();
        }
        if(s.subject==null || s.target==null)return researchUnknown(s);
        if(graph.node(s.target).kind()!=ConceptNode.Kind.CLASS ||
                (s.surface.universal() && graph.node(s.subject).kind()!=ConceptNode.Kind.CLASS))
            return result(s.id,Status.UNSUPPORTED,"Types incompatibles avec une relation ontologique ; aucune RSI enregistrée.",List.of(),List.of());
        return evaluate(s,true);
    }
    private Turn requestChoice(Session s,List<ConceptNode> choices,boolean subject){
        s.choices=List.copyOf(choices);s.choosingSubject=subject;pending=s;
        return result(s.id,Status.NEEDS_CLARIFICATION,"Quel sens veux-tu utiliser ? "+labels(choices),labels(choices),List.of());
    }
    private static List<String> labels(List<ConceptNode> nodes){return nodes.stream().map(n->n.id()+" : "+n.label()).toList();}
    private Turn researchUnknown(Session s){
        long version=graph.version();
        String terms=s.surface.subject()+" "+s.surface.target();
        if(terms.length()>180)return result(s.id,Status.NEEDS_VOCABULARY,"Concepts inconnus ; requête trop longue pour le fournisseur.",List.of(),List.of());
        List<WebSource.Hit> documents;
        try{
            var hits=provider.search(terms);
            documents=hits==null?List.of():hits.stream().filter(OntologyWebReasoningService::validHit).limit(12).toList();
        }catch(Exception ex){
            return result(s.id,Status.WEB_FAILED,"Concepts non enregistrés ; recherche documentaire échouée : "+ex.getClass().getSimpleName()+". Aucun fait ajouté.",List.of(),List.of());
        }
        if(graph.version()!=version)return result(s.id,Status.STALE_CONTEXT,"Le graphe a changé pendant la recherche ; recommencer explicitement.",List.of(),List.of());
        if(documents.isEmpty())return result(s.id,Status.NEEDS_VOCABULARY,"Concepts non enregistrés, aucun document utilisable. Définir les concepts avant d'inférer.",List.of(),List.of());
        StringBuilder answer=new StringBuilder("Documents non vérifiés pour les termes inconnus. Aucun type ni fait n'est déduit automatiquement :");
        for(var hit:documents)answer.append("\n- ").append(hit.url()).append("\n  Extrait documentaire (pas une conclusion) : ").append(hit.excerpt());
        return result(s.id,Status.DOCUMENTS_ONLY,answer.toString(),List.of(),documents);
    }
    private Turn evaluate(Session s,boolean allowWeb){
        OntologyWebReasoningService.Answer a;
        if(cycle.ontologyRepresentation(s.id).isEmpty()){
            var predicate=graph.node(s.subject).kind()==ConceptNode.Kind.CLASS?RelationAssertion.Predicate.SUBCLASS_OF:RelationAssertion.Predicate.INSTANCE_OF;
            // Une nouvelle explication de suivi dispose de sa propre RSI et d'un lien journalisé.
            a=service.resolve(s.id,s.subject,predicate,s.target,allowWeb);
        }else a=service.revisit(s.id);
        last=s;
        String explanation=a.describe();
        if(s.intent==Intent.EXPLAIN){
            explanation+="\nJustification logique relative aux prémisses ; aucune cause physique n'est établie.";
            if(a.state()==OntologyWebReasoningService.State.LOCAL_SUPPORTED && a.evaluation().isPresent()){
                var proof=a.evaluation().get().support().orElseThrow();
                if(!proof.verify(graph))return result(s.id,Status.STALE_CONTEXT,"Preuve locale périmée ; réponse suspendue.",List.of(),List.of());
                for(var step:proof.steps())explanation+="\n- "+graph.node(step.subjectId()).label()+
                    (step.predicate()==RelationAssertion.Predicate.SUBCLASS_OF?" : classe incluse dans ":" est déclaré membre de ")+
                    graph.node(step.objectId()).label()+" ["+step.id()+" ; source "+step.sourceId()+"].";
                if(proof.steps().isEmpty())explanation+="\nInclusion réflexive d'une classe dans elle-même.";
                explanation+="\nCette justification vérifie l'inférence, pas la vérité des prémisses.";
            }else explanation+="\nAucune preuve locale positive disponible ; je ne peux pas justifier l'affirmation comme établie.";
        }
        Status status=switch(a.state()) {
            case UNKNOWN, UNKNOWN_WITH_DOCUMENTS -> Status.UNDETERMINED;
            case WEB_FAILED -> Status.WEB_FAILED;
            case STALE_RESEARCH -> Status.STALE_CONTEXT;
            case LOCAL_CONTRADICTORY, WEB_CONTRADICTION -> Status.CONTRADICTORY;
            case INVALID_QUERY -> Status.UNSUPPORTED;
            default -> Status.ANSWER;
        };
        // Le service a déjà journalisé le résultat précis (LOCAL_*, WEB_*, UNKNOWN).
        return new Turn(s.id,status,explanation,List.of(),a.documents());
    }
    public Turn revisit(String id){
        Session s=sessions.get(id);
        if(s==null)throw new IllegalArgumentException("Question conversationnelle inconnue");
        if(s.subject==null || s.target==null)return result(id,Status.NEEDS_VOCABULARY,"Question encore non résolue lexicalement ; aucune recherche implicite.",List.of(),List.of());
        return evaluate(s,false);
    }
    private Turn result(String id,Status status,String text,List<String> choices,List<WebSource.Hit> documents){
        if(status!=Status.ANSWER)cycle.recordOntologyFinding(id,status.name(),graph.version());
        return new Turn(id,status,text,choices,documents);
    }
}
