package fr.ivorama.cognitive;

import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * IVOC-WEB-020 : adaptateur additif des VRAIS composants 0.6.19.
 * Le Web ne modifie jamais le graphe durable et ne certifie aucune proposition.
 */
public final class OntologyWebReasoningService {
    public enum State {
        INVALID_QUERY, LOCAL_SUPPORTED, LOCAL_REFUTED, LOCAL_CONTRADICTORY,
        UNKNOWN, UNKNOWN_WITH_DOCUMENTS, WEB_PROVISIONAL_SUPPORT, WEB_PROVISIONAL_REFUTATION,
        WEB_CONTRADICTION, WEB_FAILED, STALE_RESEARCH
    }
    public record Proposal(String id, RelationAssertion.Predicate predicate, String subject,
                           String target, boolean positive, URI url, String provider,
                           String excerpt, Instant retrievedAt) {}
    public record Answer(String questionId, State state, Optional<OntologicalInferenceEngine.Evaluation> evaluation,
                         List<WebSource.Hit> documents, List<Proposal> proposals,
                         List<String> errors, boolean fciOpen) {
        public Answer {
            documents=List.copyOf(documents);proposals=List.copyOf(proposals);errors=List.copyOf(errors);
            Objects.requireNonNull(evaluation);
        }
        public String describe() {
            StringBuilder text=new StringBuilder(questionId+" | "+state+" | FCI="+(fciOpen?"OUVERTE":"FERMÉE"));
            evaluation.ifPresent(e->{
                text.append("\nÉvaluation conditionnelle : ").append(e.verdict());
                e.support().ifPresent(p->text.append("\nPreuve positive : ").append(p.assertionIds()).append(" ; sources ").append(p.sourceIds()));
                e.refutation().ifPresent(p->text.append("\nPreuve négative : ").append(p.assertionIds()).append(" ; sources ").append(p.sourceIds()));
            });
            if(!proposals.isEmpty()){
                text.append("\nExtraits Web non vérifiés, uniquement temporaires :");
                for(Proposal p:proposals)text.append("\n- ").append(p.subject()).append(' ').append(p.positive()?"EST":"N'EST PAS")
                        .append(' ').append(p.target()).append(" selon ").append(p.url());
            }
            if(!documents.isEmpty()&&proposals.isEmpty())text.append("\nDocuments trouvés, aucun fait extractible de façon sûre.");
            for(String error:errors)text.append("\nLimite : ").append(error);
            return text.toString();
        }
    }
    private record Query(String question, String subject, String target, RelationAssertion.Predicate predicate) {}
    private final OntologicalKnowledgeGraph graph;
    private final IntegratedQuestionCycle cycle;
    private final WebSource.Provider provider;
    private final FrenchOntologicalQuestionParser parser=new FrenchOntologicalQuestionParser();
    private final OntologicalInferenceEngine inference=new OntologicalInferenceEngine();
    private final Map<String,Query> open=new LinkedHashMap<>();

    public OntologyWebReasoningService(OntologicalKnowledgeGraph graph,IntegratedQuestionCycle cycle,WebSource.Provider provider) {
        this.graph=Objects.requireNonNull(graph);this.cycle=Objects.requireNonNull(cycle);
        this.provider=Objects.requireNonNull(provider);
    }
    /** Q est enregistré avant TOUT parsing, même si le français ne peut pas être interprété. */
    public Answer ask(String question) {
        String id=cycle.openOntologyQuestion(question);
        var parsed=parser.parse(question,graph);
        if(parsed.status()!=FrenchOntologicalQuestionParser.Status.PARSED){
            return finish(id,State.INVALID_QUERY,Optional.empty(),List.of(),List.of(),
                    List.of(parsed.status()+" : "+parsed.explanation()));
        }
        return resolve(id,parsed.subject(),parsed.predicate(),parsed.target());
    }
    /** Résolution explicite d'un acte déjà ouvert, sans second Ask(Q). */
    Answer resolve(String id,String subject,RelationAssertion.Predicate predicate,String target) {
        return resolve(id,subject,predicate,target,true);
    }
    Answer resolve(String id,String subject,RelationAssertion.Predicate predicate,String target,boolean allowWeb) {
        cycle.ontologyQuestion(id);
        // Vérification de types avant toute RSI ; aucune formule propositionnelle inventée.
        inference.evaluate(graph,subject,predicate,target);
        var frame=new SemanticIntermediateRepresentation.RelationFrame(
                predicate==RelationAssertion.Predicate.INSTANCE_OF?"instance_of":"subclass_of",
                new SemanticIntermediateRepresentation.EntityRef(
                        graph.node(subject).kind()==ConceptNode.Kind.INDIVIDUAL?"individual":"class",subject),
                new SemanticIntermediateRepresentation.EntityRef("class",target),false);
        cycle.interpretOntologyQuestion(id,frame);
        open.put(id,new Query(cycle.ontologyQuestion(id).expression(),subject,target,predicate));
        return assess(id,allowWeb);
    }
    /** Réévaluation sans second Ask(Q) ; ne déclenche PAS de Web implicitement. */
    public Answer revisit(String id) {
        cycle.ontologyQuestion(id);
        if(!open.containsKey(id))return finish(id,State.INVALID_QUERY,Optional.empty(),List.of(),List.of(),
                List.of("Question non interprétée ; clarification requise"));
        return assess(id,false);
    }
    private Answer assess(String id,boolean searchWeb) {
        Query q=open.get(id);
        if(q==null)throw new IllegalArgumentException("Question inconnue: "+id);
        var local=inference.evaluate(graph,q.subject(),q.predicate(),q.target());
        if(local.verdict()!=OntologicalInferenceEngine.Verdict.UNKNOWN) {
            State status=switch(local.verdict()) {
                case SUPPORTED -> State.LOCAL_SUPPORTED;
                case REFUTED -> State.LOCAL_REFUTED;
                case CONTRADICTORY -> State.LOCAL_CONTRADICTORY;
                case UNKNOWN -> throw new IllegalStateException();
            };
            return finish(id,status,Optional.of(local),List.of(),List.of(),List.of());
        }
        if(!searchWeb)return finish(id,State.UNKNOWN,Optional.of(local),List.of(),List.of(),List.of());
        long initial=graph.version();
        List<WebSource.Hit> hits=new ArrayList<>();
        List<Proposal> proposals=new ArrayList<>();
        List<String> errors=new ArrayList<>();
        OntologicalKnowledgeGraph temporary=copyCurrent(graph);
        Set<String> urls=new HashSet<>();
        int n=0;
        Set<String> sourceIds=new HashSet<>(), assertionIds=new HashSet<>();
        temporary.sources().all().forEach(source -> sourceIds.add(source.id()));
        temporary.history().forEach(item -> assertionIds.add(item.assertion().id()));
        // RDO : investiguer des sous-classes connues de la cible avant la cible générale.
        for(String candidate:researchTargets(q.target())) {
            if(graph.version()!=initial)return stale(id);
            String terms=graph.node(q.subject()).label()+" "+graph.node(candidate).label();
            List<WebSource.Hit> results;
            try {results=provider.search(terms);} catch(Exception ex){
                errors.add("Recherche " + ex.getClass().getSimpleName()+" : "+safe(ex.getMessage())); continue;
            }
            if(graph.version()!=initial)return stale(id);
            if(results==null){errors.add("Fournisseur a renvoyé null");continue;}
            for(WebSource.Hit hit:results) {
                if(hits.size()>=12)break;
                if(!validHit(hit) || !urls.add(candidate+"|"+hit.url().toString()))continue;
                hits.add(hit);
                Optional<Boolean> positive=extract(hit.excerpt(),graph.node(q.subject()).label(),graph.node(candidate).label());
                if(positive.isEmpty())continue;
                // Type du vrai graphe historique, et source identifiée comme NON VÉRIFIÉE.
                String sid,aid;
                do { n++; sid="web_source_"+n; aid="web_assertion_"+n; }
                while(sourceIds.contains(sid) || assertionIds.contains(aid));
                sourceIds.add(sid); assertionIds.add(aid);
                temporary.sources().add(new KnowledgeSourceRegistry.Source(sid,"Extrait externe non vérifié",hit.url().toString(),
                        KnowledgeSourceRegistry.Status.UNVERIFIED));
                temporary.addAssertion(new RelationAssertion(aid,q.subject(),q.predicate(),candidate,positive.get(),sid));
                proposals.add(new Proposal(aid,q.predicate(),q.subject(),candidate,positive.get(),hit.url(),
                        hit.provider(),hit.excerpt(),hit.retrievedAt()));
            }
            if(hits.size()>=12)break;
        }
        if(graph.version()!=initial)return stale(id);
        var assessment=inference.evaluate(temporary,q.subject(),q.predicate(),q.target());
        State state=switch(assessment.verdict()) {
            case SUPPORTED -> State.WEB_PROVISIONAL_SUPPORT;
            case REFUTED -> State.WEB_PROVISIONAL_REFUTATION;
            case CONTRADICTORY -> State.WEB_CONTRADICTION;
            case UNKNOWN -> hits.isEmpty()?(errors.isEmpty()?State.UNKNOWN:State.WEB_FAILED):State.UNKNOWN_WITH_DOCUMENTS;
        };
        if(graph.version()!=initial)return stale(id);
        return finish(id,state,Optional.of(assessment),hits,proposals,errors);
    }
    private Answer stale(String id) {return finish(id,State.STALE_RESEARCH,Optional.empty(),List.of(),List.of(),
            List.of("Le graphe a changé pendant la recherche ; résultats abandonnés"));}
    private Answer finish(String id,State state,Optional<OntologicalInferenceEngine.Evaluation> assessment,
                          List<WebSource.Hit> hits,List<Proposal> proposals,List<String> errors){
        cycle.recordOntologyFinding(id,state.name(),graph.version());
        return new Answer(id,state,assessment,hits,proposals,errors,true);
    }
    private List<String> researchTargets(String target) {
        Map<String,Set<String>> predecessors=new LinkedHashMap<>();
        for(RelationAssertion a:graph.currentAssertions())
            if(a.positive() && a.predicate()==RelationAssertion.Predicate.SUBCLASS_OF)
                predecessors.computeIfAbsent(a.objectId(),k->new TreeSet<>()).add(a.subjectId());
        Set<String> found=new LinkedHashSet<>();
        ArrayDeque<String> todo=new ArrayDeque<>();todo.add(target);
        while(!todo.isEmpty()&&found.size()<16){
            String x=todo.removeFirst();
            for(String prev:predecessors.getOrDefault(x,Set.of()))
                if(!prev.equals(target)&&found.add(prev))todo.addLast(prev);
        }
        List<String> result=new ArrayList<>(found);
        if(result.size()>2)result=new ArrayList<>(result.subList(0,2));
        result.add(target);
        return result;
    }
    private static OntologicalKnowledgeGraph copyCurrent(OntologicalKnowledgeGraph g) {
        OntologicalKnowledgeGraph c=new OntologicalKnowledgeGraph();
        for(ConceptNode n:g.nodes())c.addNode(n);
        for(var s:g.sources().all())c.sources().add(s);
        for(var a:g.currentAssertions())c.addAssertion(a);
        return c;
    }
    static boolean validHit(WebSource.Hit h) {
        if(h==null||h.url()==null||h.excerpt()==null||h.provider()==null||h.retrievedAt()==null)return false;
        URI uri=h.url();
        return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null
                &&uri.getPort()==-1&&h.excerpt().length()<=1800;
    }
    private static String ascii(String text) {
        return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}+","")
                .toLowerCase(Locale.ROOT).replace('’','\'').replaceAll("\\s+"," ").strip();
    }
    /**
     * Fragment accepté : extrait entier = une assertion copulaire autonome.
     * Aucun préfixe de phrase n'est promu : compléments, citations, seconde phrase,
     * fiction et commentaires demandent une analyse sémantique encore indisponible.
     * Refuser ce fragment ne réfute pas son contenu ; le document reste consultable.
     */
    static Optional<Boolean> extract(String excerpt,String subject,String target) {
        if(excerpt==null || excerpt.length()>1800 || subject==null || target==null)
            return Optional.empty();
        String text=ascii(excerpt);
        String s=Pattern.quote(ascii(subject)),t=Pattern.quote(ascii(target));
        String article="(?:(?:une|un|le|la) |l')";
        String head="^(?:(?:le|la|un|une) |l')?"+s+" ";
        String end="[.]?$";
        if(Pattern.matches(head+"n'est pas "+article+t+end,text))return Optional.of(false);
        if(Pattern.matches(head+"est "+article+t+end,text))return Optional.of(true);
        return Optional.empty();
    }
    private static String safe(String message) {
        if(message==null)return "échec fournisseur";
        String cleaned=message.replaceAll("[\\r\\n]"," ");
        return cleaned.substring(0,Math.min(160,cleaned.length()));
    }
}
