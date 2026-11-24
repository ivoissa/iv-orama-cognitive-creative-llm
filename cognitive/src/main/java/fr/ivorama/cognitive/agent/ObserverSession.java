package fr.ivorama.cognitive.agent;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** A bounded, auditable observer workflow: propose -> validate -> read -> synthesize. */
public final class ObserverSession {
    public enum Operation { LIST, SEARCH, READ, FINISH }
    public record Action(Operation operation, String path, String query, int startLine, int lines) {
        public Action { Objects.requireNonNull(operation, "operation"); }
    }
    public record Evidence(String id, String operation, String request, String content, int scannedFiles, boolean truncated) { }
    public record Report(String answer, List<Evidence> evidence, int toolCalls, boolean evidenceLimitReached,
                         Set<String> unsupportedCitations, boolean missingCitation) {
        public Report {
            evidence = List.copyOf(evidence);
            unsupportedCitations = Set.copyOf(unsupportedCitations);
        }
    }
    @FunctionalInterface public interface Planner {
        Action nextAction(String question, List<Evidence> observations);
    }
    @FunctionalInterface public interface Synthesizer {
        String answer(String question, List<Evidence> observations);
    }
    private static final Pattern CITATIONS = Pattern.compile("\\[F(\\d{1,3})]");
    private static final int MAX_ACTIONS = 5;
    private static final int MAX_TOTAL_EVIDENCE = 6_000;
    private final fr.ivorama.cognitive.security.AgentAuthorizationGate authorization;
    private final ReadOnlyWorkspace workspace;
    private final Planner planner;
    private final Synthesizer synthesizer;
    private final boolean bootstrapListing;

    public ObserverSession(ReadOnlyWorkspace workspace, Planner planner, Synthesizer synthesizer) {
        this(workspace, planner, synthesizer, false);
    }

    /** Optional deterministic discovery counts toward the same five-tool budget. */
    public ObserverSession(ReadOnlyWorkspace workspace, Planner planner, Synthesizer synthesizer, boolean bootstrapListing) {
        this(workspace,planner,synthesizer,bootstrapListing,fr.ivorama.cognitive.security.AgentAuthorizationGate.configured());
    }

    public ObserverSession(ReadOnlyWorkspace workspace, Planner planner, Synthesizer synthesizer, boolean bootstrapListing,
                           fr.ivorama.cognitive.security.AgentAuthorizationGate authorization) {
        this.authorization=Objects.requireNonNull(authorization);
        this.bootstrapListing = bootstrapListing;
        this.workspace = Objects.requireNonNull(workspace);
        this.planner = Objects.requireNonNull(planner);
        this.synthesizer = Objects.requireNonNull(synthesizer);
    }

    public Report investigate(String question) throws IOException {
        if (question == null || question.isBlank() || question.length() > 2_000)
            throw new IllegalArgumentException("Question requise (maximum 2000 caractères)");
        List<Evidence> observations = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        int evidenceChars = 0;
        boolean reachedLimit = false;
        for (int i = 0; i < MAX_ACTIONS; i++) {
            Action candidate = i == 0 && bootstrapListing ? new Action(Operation.LIST, authorization.bootstrapPath(), "", 1, 40) : Objects.requireNonNull(planner.nextAction(question, List.copyOf(observations)),
                    "L'agent n'a proposé aucune action");
            validate(candidate);
            authorization.beforeAction(candidate,List.copyOf(observations));
            if (candidate.operation() == Operation.FINISH) break;
            if (!visited.add(actionKey(candidate))) throw new IllegalStateException("Action répétée : boucle agentique interrompue");
            ReadOnlyWorkspace.Observation out = switch (candidate.operation()) {
                case LIST -> workspace.list(candidate.path());
                case SEARCH -> workspace.search(candidate.query(),authorization.searchPath());
                case READ -> workspace.read(candidate.path(), candidate.startLine(), candidate.lines());
                case FINISH -> throw new IllegalStateException("Inatteignable");
            };
            int room = MAX_TOTAL_EVIDENCE - evidenceChars;
            if (room <= 0) { reachedLimit = true; break; }
            String content = out.content().length() > room ? out.content().substring(0, room) : out.content();
            reachedLimit |= content.length() < out.content().length();
            observations.add(new Evidence("F" + (observations.size() + 1), out.operation(), out.detail(), content,
                    out.scannedFiles(), out.truncated() || content.length() < out.content().length()));
            evidenceChars += content.length();
            if (reachedLimit || evidenceChars >= MAX_TOTAL_EVIDENCE) { reachedLimit = true; break; }
        }
        reachedLimit |= observations.size() == MAX_ACTIONS;
        authorization.beforeSynthesis(List.copyOf(observations));
        String answer = synthesizer.answer(question, List.copyOf(observations));
        if (answer == null || answer.isBlank()) throw new IllegalStateException("Synthèse vide");
        Set<String> ids = new HashSet<>();
        for (Evidence observation : observations) ids.add(observation.id());
        Set<String> unsupported = new LinkedHashSet<>();
        Matcher citations = CITATIONS.matcher(answer);
        boolean containsCitation = false;
        while (citations.find()) {
            containsCitation = true;
            String id = "F" + citations.group(1);
            if (!ids.contains(id)) unsupported.add(id);
        }
        return new Report(answer, observations, observations.size(), reachedLimit,
                unsupported, !observations.isEmpty() && !containsCitation);
    }

    /** Ignore unused JSON fields when detecting loops. */
    private static String actionKey(Action a) {
        return switch (a.operation()) {
            case SEARCH -> "SEARCH:" + a.query().toLowerCase(Locale.ROOT);
            case LIST -> "LIST:" + a.path().replace('\\', '/');
            case READ -> "READ:" + a.path().replace('\\', '/') + ":" + a.startLine() + ":" + a.lines();
            case FINISH -> "FINISH";
        };
    }

    private static void validate(Action a) {
        switch (a.operation()) {
            case LIST -> { if (a.path() == null || a.path().length() > 240) throw new IllegalArgumentException("LIST : chemin invalide"); }
            case SEARCH -> {
                if (a.query() == null || a.query().isBlank() || a.query().length() > 120)
                    throw new IllegalArgumentException("SEARCH : terme invalide");
            }
            case READ -> {
                if (a.path() == null || a.path().isBlank() || a.path().length() > 240 || a.startLine() < 1 || a.startLine() > 100_000 ||
                        a.lines() < 1 || a.lines() > 60) throw new IllegalArgumentException("READ : fenêtre invalide");
            }
            case FINISH -> { }
        }
    }
}
