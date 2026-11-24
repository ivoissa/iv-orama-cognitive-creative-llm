package fr.ivorama.cognitive.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import fr.ivorama.cognitive.HypothesisReasoningEngine;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Explicit, caller-declared conditional model. Qwen never supplies trusted premises.
 * A probe recognizes exact visible lines, not Java semantics or real-world causality.
 */
public final class ReasonedObserverSession {
    public record Probe(String signal, String path, int startLine, int lines,
                        String positiveLine, String negativeLine) {
        public Probe {
            identifier(signal);
            if (path == null || path.isBlank() || path.length() > 240 || startLine < 1 || startLine > 100_000
                    || lines < 1 || lines > 60) throw new IllegalArgumentException("Sonde invalide");
            for (String marker : List.of(Objects.requireNonNull(positiveLine), Objects.requireNonNull(negativeLine)))
                if (marker.isBlank() || marker.length() > 500 || marker.contains("\n") || marker.contains("\r"))
                    throw new IllegalArgumentException("Ligne témoin invalide");
            if (positiveLine.equals(negativeLine)) throw new IllegalArgumentException("Témoins identiques");
        }
    }
    public record Plan(String name, boolean exhaustiveUnique, Map<String, Map<String, Boolean>> hypotheses,
                       List<Probe> probes) {
        public Plan {
            identifier(name);
            if (hypotheses == null || hypotheses.size() < 2 || hypotheses.size() > 8 || probes == null
                    || probes.isEmpty() || probes.size() > 16) throw new IllegalArgumentException("Modèle hors limites");
            probes = List.copyOf(probes);
            Set<String> signals = new HashSet<>();
            for (Probe probe : probes) if (!signals.add(probe.signal())) throw new IllegalArgumentException("Sonde dupliquée");
            Map<String, Map<String, Boolean>> copy = new LinkedHashMap<>();
            int rules = 0;
            for (var entry : hypotheses.entrySet()) {
                identifier(entry.getKey());
                if (entry.getValue() == null) throw new IllegalArgumentException("Prédictions absentes");
                for (var prediction : entry.getValue().entrySet()) {
                    if (!signals.contains(prediction.getKey()) || prediction.getValue() == null)
                        throw new IllegalArgumentException("Prédiction sans sonde");
                    if (++rules > 64) throw new IllegalArgumentException("Trop de règles");
                }
                copy.put(entry.getKey(), Map.copyOf(entry.getValue()));
            }
            hypotheses = Collections.unmodifiableMap(copy);
        }
        public static Plan load(Path file) throws IOException {
            if (Files.size(file) > 32_768) throw new IllegalArgumentException("Plan trop volumineux");
            return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(Files.readString(file), Plan.class);
        }
    }
    public record Step(String signal, String before, String after, String evidenceId, String result) { }
    public record Completion(InvestigationOptions.Goal goal, boolean complete, List<String> pendingSignals,
                             List<String> unavailableSignals, List<String> unreadSignals) {
        public Completion { pendingSignals=List.copyOf(pendingSignals);unavailableSignals=List.copyOf(unavailableSignals);unreadSignals=List.copyOf(unreadSignals); }
    }
    public record Result(ObserverSession.Report report, HypothesisReasoningEngine.Finding finding,
                         List<Step> steps, List<HypothesisReasoningEngine.Observation> facts,
                         String commentaryError, Completion completion) {
        public Result { steps=List.copyOf(steps);facts=List.copyOf(facts); }
        public Result(ObserverSession.Report report,HypothesisReasoningEngine.Finding finding,List<Step> steps,
                      List<HypothesisReasoningEngine.Observation> facts,String error) {
            this(report,finding,steps,facts,error,new Completion(InvestigationOptions.Goal.DIAGNOSE,false,List.of(),List.of(),List.of()));
        }
    }
    public record Witness(String signal,boolean value,String evidenceId,String path,String line) {
        public String source() { return "["+evidenceId+"] "+path+" : "+line; }
    }
    @FunctionalInterface public interface Commentary {
        String explain(String question,HypothesisReasoningEngine.Finding finding,List<ObserverSession.Evidence> evidence);
    }
    @FunctionalInterface public interface GroundedCommentary {
        Optional<String> explain(String question,HypothesisReasoningEngine.Finding finding,List<ObserverSession.Evidence> evidence,
                       List<Witness> acceptedWitnesses,Completion completion);
    }
    public static ReasonedObserverSession grounded(ReadOnlyWorkspace workspace,Plan plan,InvestigationOptions options,GroundedCommentary commentary) {
        return new ReasonedObserverSession(workspace,plan,null,options,Objects.requireNonNull(commentary));
    }
    private final fr.ivorama.cognitive.security.AgentAuthorizationGate authorization;
    private final GroundedCommentary groundedCommentary;
    private final ReadOnlyWorkspace workspace;
    private final Plan plan;
    private final Commentary commentary;
    private final InvestigationOptions options;
    public ReasonedObserverSession(ReadOnlyWorkspace workspace,Plan plan,Commentary commentary) {
        this(workspace,plan,commentary,InvestigationOptions.defaults());
    }
    public ReasonedObserverSession(ReadOnlyWorkspace workspace,Plan plan,Commentary commentary,InvestigationOptions options) {
        this(workspace,plan,commentary,options,null);
    }
    private ReasonedObserverSession(ReadOnlyWorkspace workspace,Plan plan,Commentary commentary,InvestigationOptions options,GroundedCommentary groundedCommentary) {
        this(workspace,plan,commentary,options,groundedCommentary,fr.ivorama.cognitive.security.AgentAuthorizationGate.configured());
    }
    public static ReasonedObserverSession authorized(ReadOnlyWorkspace workspace,Plan plan,Commentary commentary,InvestigationOptions options,
            fr.ivorama.cognitive.security.AgentAuthorizationGate authorization) {
        return new ReasonedObserverSession(workspace,plan,commentary,options,null,authorization);
    }
    private ReasonedObserverSession(ReadOnlyWorkspace workspace,Plan plan,Commentary commentary,InvestigationOptions options,GroundedCommentary groundedCommentary,
            fr.ivorama.cognitive.security.AgentAuthorizationGate authorization) {
        this.authorization=Objects.requireNonNull(authorization);
        this.workspace=Objects.requireNonNull(workspace);this.plan=Objects.requireNonNull(plan);
        this.commentary=commentary;this.groundedCommentary=groundedCommentary;this.options=Objects.requireNonNull(options);
    }
    public Result investigate(String question) throws IOException {
        if(question==null||question.isBlank()||question.length()>2000)
            throw new IllegalArgumentException("Question requise (maximum 2000 caractères)");
        var engine=new HypothesisReasoningEngine(plan.name(),plan.exhaustiveUnique());
        for(String name:plan.hypotheses().keySet())engine.candidate(name);
        plan.hypotheses().forEach((name,predictions)->predictions.forEach((signal,value)->engine.predicts(name,signal,value)));
        var evidence=new ArrayList<ObserverSession.Evidence>();var steps=new ArrayList<Step>();var accepted=new ArrayList<Witness>();
        Set<String> completed=new LinkedHashSet<>(),unavailable=new LinkedHashSet<>(),unread=new LinkedHashSet<>(),knownFacts=new HashSet<>();
        Map<String,Integer> pages=new LinkedHashMap<>();int chars=0;boolean limit=false;
        String lastReadSignal=null;int consecutiveReads=0;
        while(evidence.size()<5 && chars<6000) {
            var before=engine.evaluate();
            if(before.state()==HypothesisReasoningEngine.State.RESOURCE_LIMIT)break;
            if(!options.auditsAll() && before.state()!=HypothesisReasoningEngine.State.UNDETERMINED)break;
            // Optional policies in audit mode only. LEGACY and DIAGNOSE are byte-for-byte
            // compatible in selection order; there is no claim that one fixed policy dominates.
            String signal=null;
            if(options.auditsAll() && options.probePolicy()!=InvestigationOptions.ProbePolicy.LEGACY) {
                boolean balanced=options.probePolicy()==InvestigationOptions.ProbePolicy.BALANCED;
                // Finish a bounded two-page burst of the current probe before
                // opening another. A missing/finished page cannot be retried.
                if(balanced && lastReadSignal!=null && consecutiveReads<2 && pages.containsKey(lastReadSignal))
                    signal=lastReadSignal;
                boolean freshFirst=options.probePolicy()==InvestigationOptions.ProbePolicy.COVERAGE_FIRST
                        || pages.isEmpty() || consecutiveReads>=2;
                if(signal==null && freshFirst) {
                    if(before.state()==HypothesisReasoningEngine.State.UNDETERMINED)
                        signal=engine.explore().ranked().stream().filter(s->s.guaranteedEliminated()>0)
                                .filter(s->!completed.contains(s.signal()) && !pages.containsKey(s.signal()))
                                .map(s->s.signal()).findFirst().orElse(null);
                    if(signal==null) signal=plan.probes().stream()
                            .filter(p->!completed.contains(p.signal()) && !pages.containsKey(p.signal()))
                            .map(Probe::signal).findFirst().orElse(null);
                }
            }
            if(signal==null)signal=pages.keySet().stream().findFirst().orElse(null);
            if(signal==null && before.state()==HypothesisReasoningEngine.State.UNDETERMINED)
                signal=engine.explore().ranked().stream().filter(s->s.guaranteedEliminated()>0)
                        .filter(s->!completed.contains(s.signal())).map(s->s.signal()).findFirst().orElse(null);
            if(signal==null && (options.opportunistic()||options.auditsAll()))
                signal=plan.probes().stream().filter(p->!completed.contains(p.signal()))
                        .filter(p->options.auditsAll()||plan.hypotheses().values().stream().anyMatch(h->h.containsKey(p.signal())))
                        .map(Probe::signal).findFirst().orElse(null);
            if(signal==null)break;
            if(signal.equals(lastReadSignal))consecutiveReads++;
            else {lastReadSignal=signal;consecutiveReads=1;}
            final String selected=signal;
            Probe probe=plan.probes().stream().filter(p->p.signal().equals(selected)).findFirst().orElseThrow();
            int start=pages.getOrDefault(signal,probe.startLine());
            ReadOnlyWorkspace.Observation out;
            authorization.beforeAction(new ObserverSession.Action(ObserverSession.Operation.READ,probe.path(),"",start,probe.lines()),List.copyOf(evidence));
            try { out=workspace.read(probe.path(),start,probe.lines()); }
            catch(IOException | ReadOnlyWorkspace.SourceUnavailableException | ReadOnlyWorkspace.SourceWindowUnavailableException missing) {
                String id="F"+(evidence.size()+1);String note="Lecture indisponible ; aucun fait déduit.";
                String content=note.substring(0,Math.min(6000-chars,note.length()));
                evidence.add(new ObserverSession.Evidence(id,"READ_ERROR",probe.path(),content,0,false));chars+=content.length();
                steps.add(new Step(signal,before.state().name(),before.state().name(),id,"UNAVAILABLE"));
                completed.add(signal);unavailable.add(signal);pages.remove(signal);
                if(content.length()!=note.length()){limit=true;break;}
                continue;
            }
            int room=6000-chars;String content=out.content().substring(0,Math.min(room,out.content().length()));
            boolean clipped=content.length()!=out.content().length();
            var item=new ObserverSession.Evidence("F"+(evidence.size()+1),out.operation(),out.detail(),content,out.scannedFiles(),out.truncated()||clipped);
            evidence.add(item);chars+=content.length();
            List<Witness> found=recognizedWitnesses(plan,item);boolean yes=false,no=false;
            for(Witness w:found) {
                if(!w.signal().equals(signal))continue;
                yes|=w.value();no|=!w.value();
                if(knownFacts.add(w.signal()+"="+w.value())) { engine.observe(w.signal(),w.value(),w.source());accepted.add(w); }
            }
            steps.add(new Step(signal,before.state().name(),engine.evaluate().state().name(),item.id(),yes&&no?"CONFLICT":yes?"TRUE":no?"FALSE":"UNKNOWN"));
            boolean more=out.truncated()||clipped;
            if(more)unread.add(signal);else unread.remove(signal);
            if(!clipped && options.paginate() && more && out.nextLine()>start && (options.auditsAll()||(!yes&&!no)))
                pages.put(signal,out.nextLine());
            else { pages.remove(signal);completed.add(signal); }
            if(clipped) { limit=true;break; }
        }
        var finding=engine.evaluate();
        List<String> pending=plan.probes().stream().filter(p->!completed.contains(p.signal())).map(Probe::signal).toList();
        limit|=(evidence.size()>=5||chars>=6000)&&(!pending.isEmpty()||!pages.isEmpty());
        boolean decisive=finding.state()==HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY||finding.state()==HypothesisReasoningEngine.State.INCONSISTENT;
        boolean complete=options.goal()==InvestigationOptions.Goal.DIAGNOSE
                ? decisive || (pending.isEmpty()&&unread.isEmpty()&&!limit)
                : options.goal()==InvestigationOptions.Goal.AUDIT_ALL && pending.isEmpty()&&unavailable.isEmpty()&&unread.isEmpty()&&!limit;
        var completion=new Completion(options.goal(),complete,pending,List.copyOf(unavailable),List.copyOf(unread));
        String conclusion=switch(finding.state()) {
            case PROVEN_CONDITIONALLY -> "Hypothèse retenue sous le modèle déclaré : "+finding.cause()+".";
            case INCONSISTENT -> "Les observations sont incompatibles sous le modèle déclaré ; le diagnostic est suspendu.";
            case UNDETERMINED -> "Les observations ne suffisent pas à identifier une hypothèse unique.";
            default -> "Le diagnostic ne peut pas aboutir dans les limites disponibles.";
        };
        String receipt=conclusion+"\nNOYAU IV-ORAMA : "+finding.state()+"\nObjectif : "+options.goal()
                +" ; recherche bornée achevée="+complete+"\nCompatibles : "+finding.possible()+" ; réfutées : "+finding.refuted()
                +"\n"+finding.reason()+"\nPortée : modèle fourni par l'appelant ; témoins textuels exacts, pas de validation sémantique du code."
                +"\nObservations retenues :\n"+String.join("\n",engine.observations().stream().map(HypothesisReasoningEngine.Observation::source).toList());
        for(Step step:steps)if(Set.of("UNKNOWN","UNAVAILABLE").contains(step.result())) {
            Probe p=plan.probes().stream().filter(x->x.signal().equals(step.signal())).findFirst().orElseThrow();
            receipt+="\n["+step.evidenceId()+"] "+p.path()+(step.result().equals("UNAVAILABLE")?" : lecture indisponible ; signal inconnu.":" : aucun témoin reconnu dans cette fenêtre ; signal inconnu.");
        }
        if(!pending.isEmpty())receipt+="\nSondes non achevées : "+pending;
        if(!unread.isEmpty())receipt+="\nSources partiellement lues : "+unread;
        if(options.goal()==InvestigationOptions.Goal.REVIEW_MODEL)
            receipt+="\nLa validité sémantique du modèle nécessite un contrôle indépendant ; aucune critique générée ne devient une prémisse.";
        authorization.beforeSynthesis(List.copyOf(evidence));
        authorization.beforeReasonedComment(plan,new Result(
                new ObserverSession.Report(receipt,evidence,evidence.size(),limit,Set.of(),false),
                finding,steps,engine.observations(),"",completion));
        String error="";
        if(commentary!=null||groundedCommentary!=null)try {
            Optional<String> generated=groundedCommentary==null?Optional.ofNullable(commentary.explain(question,finding,List.copyOf(evidence)))
                    :Objects.requireNonNull(groundedCommentary.explain(question,finding,List.copyOf(evidence),List.copyOf(accepted),completion));
            if(generated.isEmpty() && groundedCommentary!=null)
                receipt+="\nRésultat restitué sans commentaire supplémentaire.";
            else {
                String text=generated.orElseThrow(()->new IllegalStateException("Commentaire vide"));
                if(text.isBlank())throw new IllegalStateException("Commentaire vide");
                receipt+="\n\nCOMMENTAIRE QWEN NON CERTIFIÉ :\n"+text;
            }
        } catch(RuntimeException failure) {
            error=failure.getClass().getSimpleName()+": "+failure.getMessage();
            receipt+="\nLe commentaire complémentaire n’a pas été retenu ; le résultat du noyau reste disponible.";
        }
        Set<String> known=new HashSet<>(),unsupported=new LinkedHashSet<>();evidence.forEach(e->known.add(e.id()));
        var citations=java.util.regex.Pattern.compile("\\[F([0-9]{1,3})]").matcher(receipt);
        while(citations.find())if(!known.contains("F"+citations.group(1)))unsupported.add("F"+citations.group(1));
        return new Result(new ObserverSession.Report(receipt,evidence,evidence.size(),limit,unsupported,false),finding,steps,engine.observations(),error,completion);
    }
    /** Exact recognition shared by the observer and the generated-commentary view. */
    public static List<Witness> recognizedWitnesses(Plan plan,ObserverSession.Evidence evidence) {
        if(!evidence.operation().equals("READ"))return List.of();
        var found=new ArrayList<Witness>();
        for(Probe probe:plan.probes())if(canonical(probe.path()).equals(canonical(evidence.request()))) {
            if(witness(evidence.content(),evidence.request(),probe.positiveLine()))found.add(new Witness(probe.signal(),true,evidence.id(),evidence.request(),probe.positiveLine()));
            if(witness(evidence.content(),evidence.request(),probe.negativeLine()))found.add(new Witness(probe.signal(),false,evidence.id(),evidence.request(),probe.negativeLine()));
        }
        return List.copyOf(found);
    }
    /** Normalized facts only: ignored near-matches and source instructions never become commentary inputs. */
    public static List<ObserverSession.Evidence> commentaryEvidence(List<ObserverSession.Evidence> evidence,List<Witness> accepted) {
        return evidence.stream().map(e->{
            List<Witness> facts=accepted.stream().filter(w->w.evidenceId().equals(e.id())).toList();
            String text=facts.isEmpty()?"UNKNOWN : aucune valeur reconnue par le noyau dans cette lecture."
                    :String.join("\n",facts.stream().map(w->w.signal()+"="+(w.value()?"TRUE":"FALSE")+" (témoin textuel reconnu, portée conditionnelle)").toList());
            if(e.operation().equals("READ_ERROR"))text="UNAVAILABLE : lecture indisponible ; aucun fait déduit.";
            return new ObserverSession.Evidence(e.id(),e.operation(),e.request(),text,e.scannedFiles(),e.truncated());
        }).toList();
    }
    private static String canonical(String path) { return Path.of(path.replace('\\','/')).normalize().toString().replace('\\','/'); }
    private static boolean witness(String content,String path,String expected) {
        return java.util.regex.Pattern.compile("^"+java.util.regex.Pattern.quote(path)+":[0-9]+: "+java.util.regex.Pattern.quote(expected)+"\\n",java.util.regex.Pattern.MULTILINE).matcher(content).find();
    }
    private static void identifier(String text) {
        if(text==null||!text.matches("[a-z][a-z0-9_]{0,39}"))throw new IllegalArgumentException("Identifiant invalide");
    }
}
