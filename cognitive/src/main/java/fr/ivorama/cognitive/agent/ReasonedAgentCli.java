package fr.ivorama.cognitive.agent;

import java.nio.file.Path;

/** Offline entry point; no Ollama dependency. Plan is supplied explicitly, never discovered in a repository. */
public final class ReasonedAgentCli {
    private ReasonedAgentCli() { }
    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("<dossier> <plan.json> <question>");
        var result = new ReasonedObserverSession(new ReadOnlyWorkspace(Path.of(args[0])),
                ReasonedObserverSession.Plan.load(Path.of(args[1])), null, InvestigationOptions.fromSystemProperties())
                .investigate(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
        System.out.println(result.report().answer());
        result.steps().forEach(System.out::println);
    }
}
