package fr.ivorama.cognitive.generation;

import fr.ivorama.cognitive.HypothesisReasoningEngine;
import fr.ivorama.cognitive.agent.*;
import java.util.*;
import java.util.regex.*;

/**
 * Narrow, deterministic semantic guard for French, *closed-form* diagnostic sentences.
 * Uses the unchanged IV-ORAMA agent decision verifier; it does not understand arbitrary prose.
 * In particular, it neither certifies empirical truth nor interprets quotes/code/multi-clause sentences.
 * It never calls a LLM and never writes files. All edits are opt-in recommendations.
 */
public final class ReasonedGenerativeGuard {
    private ReasonedGenerativeGuard() { }
    public enum Status { UNCHANGED, ALERT_ONLY, CORRECTION_PROPOSED, NOT_VERIFIABLE }
    public record Edit(String before, String after, String reason) { }
    public record Warning(String excerpt, String reason) { }
    public record Review(String original, String suggestion, Status status, List<Edit> edits, List<Warning> warnings,
                         int recognizedSentences, String limitation) {
        public Review { edits = List.copyOf(edits); warnings=List.copyOf(warnings); }
    }

    // Intentionally limited to single-sentence declarative shapes with one ASCII model ID.
    // The left side's semantics are controlled; do not extend this using fuzzy substring matching.
    private static final Pattern CAUSE=Pattern.compile(
            "^(?:La cause(?: racine| principale| confirmée)?|La piste retenue|La seule hypothèse compatible)\\s+(?:est|serait)\\s+«?([a-z][a-z0-9_]{0,39})»?[.!?]$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern AUDIT=Pattern.compile(
            "^(?:L['’]audit est (?:complet|terminé)|L['’]enquête est (?:complète|terminée)|Toutes les sources ont été examinées)[.!?]$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMPLEX_AUDIT=Pattern.compile(
            "^(?:Le contrôle est complet|L['’]audit est complet|L['’]enquête est complète)\\s+(?:car|puisque|mais|tandis que)\\b.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ABSTENTION=Pattern.compile(
            "^(?:Impossible de conclure|Aucune hypothèse ne peut être départagée)[.!?]$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static Review review(ReasonedObserverSession.Plan plan, ReasonedObserverSession.Result result, String draft) {
        Objects.requireNonNull(plan); Objects.requireNonNull(result); Objects.requireNonNull(draft);
        if (draft.isBlank() || draft.length()>8000 || draft.contains("```") || draft.indexOf('\0')>=0)
            return new Review(draft,draft,Status.NOT_VERIFIABLE,List.of(),List.of(),0,"Texte vide, trop long ou contenant un bloc de code");
        AgentDecisionVerifier.Verdict ground=verifiedGround(plan,result);
        if (ground == null || !ground.accepted())
            return new Review(draft,draft,Status.NOT_VERIFIABLE,List.of(),List.of(),0,"Intégrité des observations ou preuves non établie");
        String[] sentences=draft.split("(?<=[.!?])(?=\\s)|(?<=\\n)",-1);
        var edits=new ArrayList<Edit>();
        var warnings=new ArrayList<Warning>();
        int recognized=0;
        var revised=new StringBuilder();
        for (String fragment: sentences) {
            String candidate=fragment.strip();
            // An interrogative asks for a conclusion; it does not assert that conclusion.
            if (candidate.endsWith("?")) { revised.append(fragment); continue; }
            // Protect quotations, Markdown, lists and multi-clause passages. If such prose contains a
            // false claim, preserve it rather than pretending that its meaning was parsed.
            boolean safe=!candidate.startsWith(">") && !candidate.startsWith("\"") &&
                    !candidate.startsWith("-") && !candidate.startsWith("*") && !candidate.contains(";") &&
                    !candidate.contains(",") && !candidate.contains(" et ") && !candidate.contains(" mais ");
            String replacement=null,reason="";
            if (safe) {
                Matcher cause=CAUSE.matcher(candidate);
                if (cause.matches()) {
                    recognized++;
                    String claimed=cause.group(1).toLowerCase(Locale.ROOT);
                    var finding=result.finding();
                    boolean actualConditional=finding.state()==HypothesisReasoningEngine.State.PROVEN_CONDITIONALLY;
                    boolean correct=actualConditional && claimed.equals(finding.cause());
                    // A bare "La cause est X" asserts real-world causality; a compatible *hypothesis*
                    // does not. Preserve an already precise conditional sentence.
                    boolean properlyQualified=candidate.toLowerCase(Locale.ROOT).startsWith("la seule hypothèse compatible");
                    if (!correct || !properlyQualified) {
                        replacement=certifiedSentence(result);
                        reason=!correct?"CAUSE_INCOMPATIBLE_OU_NON_ETABLIE":"CERTITUDE_EMPIRIQUE_NON_JUSTIFIEE";
                    }
                } else if (ABSTENTION.matcher(candidate).matches()) {
                    recognized++;
                    if (result.finding().state()!=HypothesisReasoningEngine.State.UNDETERMINED) {
                        replacement=certifiedSentence(result);
                        reason="ABSTENTION_NON_JUSTIFIEE_PAR_LE_MODELE";
                    }
                } else if (AUDIT.matcher(candidate).matches()) {
                    recognized++;
                    boolean complete=result.completion().goal()==InvestigationOptions.Goal.AUDIT_ALL &&
                            result.completion().complete() && !result.report().evidenceLimitReached() &&
                            result.completion().pendingSignals().isEmpty() &&
                            result.completion().unavailableSignals().isEmpty() &&
                            result.completion().unreadSignals().isEmpty();
                    if (!complete) {
                        replacement=result.completion().goal()!=InvestigationOptions.Goal.AUDIT_ALL
                                ?"L'exhaustivité de l'audit n'est pas établie par le mode d'enquête exécuté."
                                :"L'audit prévu n'est pas complet ; certaines lectures sont inachevées ou indisponibles.";
                        reason="AUDIT_COMPLET_NON_ETABLI";
                    }
                }
            }
            // A complex sentence may carry the same false completeness assertion, but editing
            // the entire sentence would discard information; warn only and retain it intact.
            if (replacement==null && !candidate.isBlank() && COMPLEX_AUDIT.matcher(candidate).matches()
                    && !(result.completion().goal()==InvestigationOptions.Goal.AUDIT_ALL &&
                        result.completion().complete() && !result.report().evidenceLimitReached() &&
                        result.completion().pendingSignals().isEmpty() &&
                        result.completion().unavailableSignals().isEmpty() &&
                        result.completion().unreadSignals().isEmpty())) {
                recognized++;
                warnings.add(new Warning(candidate,"EXHAUSTIVITE_NON_JUSTIFIEE_POUR_PHRASE_COMPLEXE"));
            }
            if(replacement!=null && !candidate.equals(replacement) && edits.size()<3) {
                edits.add(new Edit(candidate,replacement,reason));
                int from=fragment.indexOf(candidate);
                revised.append(fragment,0,from).append(replacement).append(fragment.substring(from+candidate.length()));
            } else revised.append(fragment);
        }
        return new Review(draft,revised.toString(),!edits.isEmpty()?Status.CORRECTION_PROPOSED
                    :warnings.isEmpty()?(recognized==0?Status.NOT_VERIFIABLE:Status.UNCHANGED):Status.ALERT_ONLY,
                edits,warnings,recognized,"Seulement certaines phrases françaises isolées sont analysées ; les autres restent non vérifiées");
    }

    private static AgentDecisionVerifier.Verdict verifiedGround(ReasonedObserverSession.Plan plan,
                                                                ReasonedObserverSession.Result result) {
        var seenSignal=new HashMap<String,String>();
        for(var step: result.steps())seenSignal.put(step.evidenceId(),step.signal());
        var ids=new LinkedHashSet<String>();
        for(var evidence:result.report().evidence()) {
            boolean present=ReasonedObserverSession.recognizedWitnesses(plan,evidence).stream()
                    .anyMatch(w->w.signal().equals(seenSignal.get(evidence.id())));
            if (present) ids.add(evidence.id());
        }
        if (ids.size()>5)return null;
        var finding=result.finding();
        AgentDecisionVerifier.Kind kind=switch(finding.state()) {
            case PROVEN_CONDITIONALLY -> AgentDecisionVerifier.Kind.CAUSE;
            case INCONSISTENT -> AgentDecisionVerifier.Kind.INCONSISTENT;
            case UNDETERMINED -> AgentDecisionVerifier.Kind.INDETERMINATE;
            default -> null;
        };
        if (kind==null) return null;
        return AgentDecisionVerifier.verify(plan,result,new AgentDecisionVerifier.Proposal(
                kind,kind==AgentDecisionVerifier.Kind.CAUSE?finding.cause():"",List.copyOf(ids),false));
    }

    private static String certifiedSentence(ReasonedObserverSession.Result result) {
        return switch(result.finding().state()) {
            case PROVEN_CONDITIONALLY -> "Sous le modèle déclaré et les observations reconnues, la seule hypothèse compatible est «"
                    +result.finding().cause()+"» ; sa causalité réelle n'est pas démontrée.";
            case UNDETERMINED -> "Les observations reconnues ne permettent pas de départager les hypothèses sous le modèle déclaré.";
            case INCONSISTENT -> "Les observations sont contradictoires sous le modèle déclaré ; aucune cause n'est confirmée.";
            default -> throw new IllegalStateException("État non certifiable");
        };
    }
}
