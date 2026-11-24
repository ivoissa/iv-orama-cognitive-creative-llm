package fr.ivorama.cognitive.generative;

import java.util.*;

/** Java-only application entry point. Ollama and model weights are separate prerequisites. */
public final class LocalAssistantCli {
    private LocalAssistantCli(){}
    enum Mode { NATIVE, ENRICHED, CHECK, OBSERVE, PROPOSE, VALIDATE, REASONED }
    static Mode modeOf(String[] args) {
        if (args.length == 0) return Mode.NATIVE;
        return switch (args[0]) {
            case "--chat" -> single(args, Mode.NATIVE);
            case "--chat-iv" -> single(args, Mode.ENRICHED);
            case "--check" -> single(args, Mode.CHECK);
            case "--observe", "--propose" -> {
                if (args.length < 3) throw new IllegalArgumentException("Dossier et question requis");
                yield args[0].equals("--observe") ? Mode.OBSERVE : Mode.PROPOSE;
            }
            case "--observe-reasoned" -> {
                if (args.length < 4) throw new IllegalArgumentException("Dossier, plan et question requis");
                yield Mode.REASONED;
            }
            case "--propose-validate" -> {
                if (args.length < 4) throw new IllegalArgumentException("Dossier, classes de tests et question requis");
                yield Mode.VALIDATE;
            }
            default -> throw new IllegalArgumentException("Mode inconnu : --chat, --chat-iv, --check, --observe, --propose ou --propose-validate");
        };
    }
    private static Mode single(String[] args, Mode mode) {
        if (args.length != 1) throw new IllegalArgumentException("Aucun argument supplémentaire pour ce mode");
        return mode;
    }
    public static void main(String[] args)throws Exception{
        var yaml = com.ivorama.nlp.transform.TransformationConfigLoader.defaultPath();
        if (args.length > 0 && args[0].equals("--config")) {
            if (args.length < 2) throw new IllegalArgumentException("--config exige un chemin YAML");
            yaml = java.nio.file.Path.of(args[1]);
            args = Arrays.copyOfRange(args, 2, args.length);
        }
        var mode = modeOf(args);
        try (var settings = AssistantYaml.configure(yaml)) {
            run(args, mode, yaml, settings.renderEnabled());
        }
    }

    private static void run(String[] args, Mode mode, java.nio.file.Path yaml, boolean renderEnabled)
            throws Exception {
        String provider = System.getProperty("ivorama.llm.provider", "ollama");
        if (provider.equals("openai-compatible") || provider.equals("openai")) {
            runCompatible(mode, yaml, renderEnabled);
            return;
        }
        if (!provider.equals("ollama")) throw new IllegalArgumentException("Fournisseur LLM inconnu");
        var config=LocalLlmConfig.fromSystemProperties();var probe=new OllamaProbe(config);
        System.out.println("IV-ORAMA 0.7.12 experiment — Qwen natif par défaut ; IV sur demande explicite");
        System.out.println("Moteur : "+probe.get("/api/version"));
        System.out.println("Modèle : "+probe.requireInstalled().path("name").asText());
        if(mode == Mode.CHECK)return;
        if(mode == Mode.REASONED) {
            var plan = fr.ivorama.cognitive.agent.ReasonedObserverSession.Plan.load(java.nio.file.Path.of(args[2]));
            try (var telemetry = fr.ivorama.cognitive.generation.TurnTelemetry.start()) {
                var result = QwenObserverAgent.investigateReasoned(java.nio.file.Path.of(args[1]), plan,
                        String.join(" ", Arrays.copyOfRange(args, 3, args.length)), OllamaGenerationBackend.reasonedComment(
                                config.withLimits(Math.min(256, config.outputTokens()), config.timeout().compareTo(java.time.Duration.ofSeconds(90)) > 0
                                        ? java.time.Duration.ofSeconds(90) : config.timeout())));
                System.out.println(result.report().answer());
                result.steps().forEach(System.out::println);
                // Read-only, opt-in review of the actual *Qwen commentary* only.
                // The observer's report, native chat, and all underlying model facts stay unchanged.
                if (Boolean.parseBoolean(System.getProperty("ivorama.llm.reviewReasonedText","false"))) {
                    String label="COMMENTAIRE QWEN NON CERTIFIÉ :\n";
                    String report=result.report().answer();
                    int offset=report.lastIndexOf(label);
                    System.out.println("--- IV-ORAMA : contrôle limité de rédaction ---");
                    if(offset<0) System.out.println("Aucun commentaire Qwen à inspecter ; réponse originale conservée.");
                    else {
                        // Remove only the renderer's known metadata prefix. The original report
                        // above is retained exactly; the guard inspects the actual commentary text.
                        String commentary=report.substring(offset+label.length());
                        String renderedPrefix="Commentaire généré, sens non certifié : ";
                        if(commentary.startsWith(renderedPrefix))commentary=commentary.substring(renderedPrefix.length());
                        var reviewed=fr.ivorama.cognitive.generation.ReasonedGenerativeGuard.review(
                                plan,result,commentary);
                        System.out.println("Statut="+reviewed.status()+" ; phrases reconnues="+reviewed.recognizedSentences()
                                +" ; corrections proposées="+reviewed.edits().size());
                        reviewed.warnings().forEach(warning -> System.out.println("ALERTE NON CORRIGÉE : "
                                +warning.reason()+" ; extrait=«"+warning.excerpt()+"»"));
                        if(!reviewed.edits().isEmpty()) {
                            reviewed.edits().forEach(edit -> System.out.println("Motif="+edit.reason()+" ; source=«"
                                    +edit.before()+"» ; suggestion=«"+edit.after()+"»"));
                            System.out.println("--- Texte proposé (pas de remplacement automatique) ---\n"+reviewed.suggestion());
                        }
                        System.out.println("Périmètre : "+reviewed.limitation()+". Causalité empirique non certifiée.");
                    }
                }
                // Strictly opt-in: never changes native chat or the observer's result.
                var decisionAuditMode=fr.ivorama.cognitive.agent.AgentDecisionVerifier.auditModeFromProperties();
                if(decisionAuditMode==fr.ivorama.cognitive.agent.AgentDecisionVerifier.AuditMode.DIRECT) {
                    var verdict=fr.ivorama.cognitive.agent.AgentDecisionVerifier.verifyObservedDecision(plan,result);
                    System.out.println("--- IV-ORAMA : décision effectivement utilisée (0 appel Qwen supplémentaire) ---");
                    System.out.println("Etat="+result.finding().state()+" ; cause="+result.finding().cause());
                    System.out.println("Verdict="+verdict.status()+" ; erreurs="+verdict.errors());
                    System.out.println("Témoins minimaux reconnus="+verdict.verifiedEvidenceIds());
                    System.out.println("Contrôle de cohérence du reçu et du modèle fourni, PAS de la vérité externe ni du texte libre.");
                } else if(decisionAuditMode==fr.ivorama.cognitive.agent.AgentDecisionVerifier.AuditMode.QWEN) {
                    var audit = QwenDecisionAudit.audit(plan,result,
                            String.join(" ", Arrays.copyOfRange(args, 3, args.length)),
                            new OllamaGenerationBackend(config.withLimits(256,
                                    config.timeout().compareTo(java.time.Duration.ofSeconds(90))>0
                                            ?java.time.Duration.ofSeconds(90):config.timeout()),true));
                    System.out.println("--- IV-ORAMA : vérification de décision conditionnelle ---");
                    if(audit.verdict()!=null) {
                        System.out.println("Proposition="+audit.proposal().kind()+" ; cause="+audit.proposal().cause());
                        System.out.println("Verdict="+audit.verdict().status()+" ; erreurs="+audit.verdict().errors());
                        System.out.println("Preuves effectivement reconnues="+audit.verdict().verifiedEvidenceIds());
                    } else System.out.println(audit.failure()+" ; diagnostic original conservé.");
                    System.out.println("Ni vérité empirique, ni autorisation d'action. Aucun effet sur Qwen natif.");
                }
                printAgentMetrics(telemetry.snapshot());
            }
            return;
        }
        if(mode == Mode.PROPOSE || mode == Mode.VALIDATE){
            boolean validate = mode == Mode.VALIDATE;
            if (validate && args.length < 4) throw new IllegalArgumentException("--propose-validate <dossier> <classes-de-tests> <question>");
            String question=String.join(" ",java.util.Arrays.copyOfRange(args,validate?3:2,args.length));
            var plannerConfig=new LocalLlmConfig(config.endpoint(),config.model(),config.contextTokens(),
                    256,config.threads(),config.memoryMessages(),config.timeout(),0.0);
            var patchConfig=new LocalLlmConfig(config.endpoint(),config.model(),config.contextTokens(),
                    1536,config.threads(),config.memoryMessages(),config.timeout(),0.0);
            var agent=new QwenPatchProposalAgent(java.nio.file.Path.of(args[1]),
                    OllamaGenerationBackend.observerPlan(plannerConfig),
                    new OllamaGenerationBackend(patchConfig,true));
            var plan = validate ? new fr.ivorama.cognitive.agent.IsolatedPatchValidator.Plan(
                    System.getProperty("ivorama.agent.maven", "mvn"), args[2],
                    java.time.Duration.ofSeconds(Long.parseLong(System.getProperty("ivorama.agent.validationTimeout", "120"))), true) : null;
            QwenPatchProposalAgent.ProposalResult draft;
            try (var telemetry = fr.ivorama.cognitive.generation.TurnTelemetry.start()) {
                draft=agent.propose(question, plan);
                printAgentMetrics(telemetry.snapshot());
            }
            System.out.println("--- Analyse non certifiée ---\n"+draft.rationale());
            System.out.println("Observations de lecture : "+draft.observations().toolCalls());
            if (draft.stagedFiles().isEmpty()) System.out.println("Abstention : aucune correction proposée sur ces observations.");
            for(var f:draft.stagedFiles()){
                System.out.println("\n--- Fichier proposé : "+f.path()+" ---");
                System.out.println("SHA-256 avant : "+f.originalSha256()+" ; brouillon : "+f.stagedSha256());
                System.out.println("Syntaxe Java 21 : "+(f.syntaxValid()?"PARSE_OK":"ERREUR_SYNTAXE"));
                for(String e:f.parseErrors())System.out.println("  "+e);
                System.out.println(f.preview());
            }
            System.out.println("Aucun fichier du dépôt modifié. Copies temporaires effacées.");
            System.out.println("Validation : " + draft.validation().status() + " ; " + draft.validation().detail());
            if (draft.validation().baseline() != null) System.out.println("Référence : " + draft.validation().baseline());
            if (draft.validation().candidate() != null) System.out.println("Candidat : " + draft.validation().candidate());
            return;
        }
        if(mode == Mode.OBSERVE){
            String question=String.join(" ",java.util.Arrays.copyOfRange(args,2,args.length));
            var agent=new QwenObserverAgent(java.nio.file.Path.of(args[1]),
                    OllamaGenerationBackend.observerPlan(new LocalLlmConfig(config.endpoint(),config.model(),config.contextTokens(),
                            256,config.threads(),config.memoryMessages(),config.timeout(),0.0)),
                    new OllamaGenerationBackend(config,true));
            fr.ivorama.cognitive.agent.ObserverSession.Report report;
            try (var telemetry = fr.ivorama.cognitive.generation.TurnTelemetry.start()) {
                report=agent.investigate(question);
                printAgentMetrics(telemetry.snapshot());
            }
            System.out.println(report.answer());
            System.out.println("--- Observations locales (lecture seule) ---");
            for(var e:report.evidence())System.out.println("["+e.id()+"] "+e.operation()+" "+e.request()+
                    " ; fichiers inspectés : "+e.scannedFiles()+" ; tronqué : "+e.truncated());
            System.out.println("Outils exécutés : "+report.toolCalls()+" ; budget atteint : "+report.evidenceLimitReached());
            if(report.missingCitation())System.out.println("ATTENTION : synthèse sans citation de fichier.");
            if(!report.unsupportedCitations().isEmpty())System.out.println("ATTENTION : citations inexistantes : "+report.unsupportedCitations());
            System.out.println("Synthèse Qwen non certifiée, aucune modification ni test de code exécuté.");
            return;
        }
        if (mode == Mode.NATIVE) {
            nativeChat(config, new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8), System.out);
            return;
        }
        var plannerConfig = new LocalLlmConfig(config.endpoint(),config.model(),config.contextTokens(),128,config.threads(),config.memoryMessages(),config.timeout(),0.0);
        var interpretationConfig = new LocalLlmConfig(config.endpoint(),config.model(),config.contextTokens(),512,config.threads(),config.memoryMessages(),config.timeout(),0.0);
        enriched(yaml, renderEnabled, config.memoryMessages(),
                new OllamaGenerationBackend(config), new OllamaGenerationBackend(config,true),
                new OllamaGenerationBackend(plannerConfig,true), new OllamaGenerationBackend(interpretationConfig,true));
    }

    private static void runCompatible(Mode mode, java.nio.file.Path yaml, boolean renderEnabled) {
        if (mode != Mode.NATIVE && mode != Mode.ENRICHED)
            throw new IllegalArgumentException("Fournisseur compatible OpenAI disponible pour --chat et --chat-iv");
        var base = java.net.URI.create(System.getProperty("ivorama.llm.url", "https://api.openai.com/v1"));
        String model = System.getProperty("ivorama.llm.model", "gpt-5.1");
        String key = System.getenv(System.getProperty("ivorama.llm.apiKeyEnv", "OPENAI_API_KEY"));
        int tokens = Integer.getInteger("ivorama.llm.output", 768);
        int memory = Integer.getInteger("ivorama.llm.memory", 17);
        var timeout = java.time.Duration.ofSeconds(Integer.getInteger("ivorama.llm.timeout", 300));
        var backend = new OpenAiCompatibleBackend(base, key, model, timeout, tokens, false);
        if (mode == Mode.NATIVE) {
            nativeChat(new NativeConversation(backend, memory), new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8), System.out);
            return;
        }
        enriched(yaml, renderEnabled, memory, backend,
                new OpenAiCompatibleBackend(base, key, model, timeout, tokens, true),
                new OpenAiCompatibleBackend(base, key, model, timeout, Math.min(128, tokens), true),
                new OpenAiCompatibleBackend(base, key, model, timeout, Math.min(512, tokens), true));
    }

    private static void enriched(java.nio.file.Path yaml, boolean renderEnabled, int memory,
                                 GenerationBackend backend, GenerationBackend repair,
                                 GenerationBackend planner, GenerationBackend interpreter) {
        var renderer = renderEnabled
                ? new fr.ivorama.cognitive.render.LiteTextRenderer(yaml) : null;
        boolean webEnabled = Boolean.parseBoolean(System.getProperty("ivorama.llm.wikipedia", "false"));
        var conversation = new GroundedConversation(new LocalConversation(backend, repair, memory),
                new WikipediaResearch(planner, new fr.ivorama.cognitive.WebSource.Wikipedia(), webEnabled),
                Boolean.parseBoolean(System.getProperty("ivorama.llm.interpret", "false")) ? new RequestPlanner(interpreter) : null,
                Boolean.parseBoolean(System.getProperty("ivorama.llm.autoReason", "false")),
                Boolean.parseBoolean(System.getProperty("ivorama.llm.candidate073", System.getProperty("ivorama.llm.candidate072", "true")))
                        ? GroundedConversation.Options.candidate073() : GroundedConversation.Options.legacy());
        System.out.println("Commandes : :nouveau, :quitter, :raisonner <français contrôlé ou JSON>, :analyser <texte>. Planificateur optionnel désactivé par défaut ; contraintes de sortie contrôlées, une reprise maximum. Routage formel automatique des messages entièrement reconnus avec prémisses ; autres réponses non certifiées.");
        try(var scanner=new Scanner(System.in,java.nio.charset.StandardCharsets.UTF_8)){
            while(true){
                System.out.print("Vous > ");if(!scanner.hasNextLine())break;String input=scanner.nextLine();
                if(input.equals(":quitter"))break;if(input.equals(":nouveau")){conversation.reset();continue;}if(input.isBlank())continue;
                System.out.print("IV-ORAMA > ");
                try{
                    var turn = conversation.ask(input, renderer == null ? System.out::print : ignored -> {});
                    var result = turn.generation();
                    if (renderer != null) {
                        String answer = result.text();
                        // Technical and structured responses retain their exact formatting.
                        if (answer.matches("[\\p{L} ,.!?…’'–—-]+")) answer = renderer.renderPlainText(answer);
                        System.out.print(answer);
                    }
                    System.out.println();
                    System.out.println("Appels modèle (tous essais) : "+turn.telemetry().calls().size()+
                            " ; jetons connus entrée/sortie : "+turn.telemetry().knownInputTokens()+"/"+turn.telemetry().knownOutputTokens()+
                            " ; comptage complet : "+turn.telemetry().completeTokenAccounting());
                    System.out.println("Contrôles : "+turn.verification().report().checks()+" ; essais : "+turn.verification().attempts());
                    turn.formal().ifPresent(f->System.out.println("Noyau : "+f.status()+" ; route : "+f.route()+" ; preuve syntaxique vérifiée : "+f.proofChecked()));
                    System.out.println("Interprétation : "+turn.interpretation().status()+" ; tâches proposées : "+(turn.interpretation().usable()?turn.interpretation().plan().tasks():"indisponibles"));
                    System.out.println("Recherche : "+turn.research().state()+" ; termes : "+turn.research().terms());
                    for(var source:turn.research().sources())System.out.println("["+source.id()+"] "+source.title()+" | "+source.url()+" | consulté "+source.retrievedAt());
                    if(!turn.citationAudit().unsupportedIds().isEmpty())System.out.println("Attention : identifiants de citation sans source reçue : "+turn.citationAudit().unsupportedIds());
                    if(turn.citationAudit().documentsWithoutCitation())System.out.println("Attention : documents récupérés mais aucune citation reconnue dans la réponse.");
                    if(!turn.research().sources().isEmpty())System.out.println("Documents des contributeurs de Wikipédia ; voir les articles pour l'historique et la licence. Attribution et exactitude du texte généré non certifiées.");
                    System.out.printf(Locale.ROOT,"[Temps complet, recherche comprise : %.2f s]%n",turn.totalNanos()/1e9);
                    System.out.printf(Locale.ROOT,"[%.2f s ; premier texte %.2f s ; %s tokens générés ; %s]%n",result.wallNanos()/1e9,
                            result.firstTextNanos()<0?-1:result.firstTextNanos()/1e9,result.outputTokens(),result.finishReason());
                    if(result.truncated())System.out.println("Réponse interrompue par la limite de sortie ; ce tour n'est pas conservé.");
                }catch(RuntimeException ex){
                    var failure=new ConversationOutcome<Object>(ConversationOutcome.classify(ex),null);
                    System.out.println("\n["+failure.status()+"] "+failure.explanation());
                }
            }
        }
    }
    static void nativeChat(LocalLlmConfig config, Scanner input, java.io.PrintStream output) {
        nativeChat(NativeConversation.ollama(config), input, output);
    }
    static void nativeChat(NativeConversation conversation, Scanner input, java.io.PrintStream output) {
        output.println("Qwen natif : :nouveau, :quitter. Aucune recherche, consigne IV ou reprise automatique. Historique complet jusqu'à réinitialisation ; contexte Ollama configuré.");
        while (true) {
            output.print("Vous > ");
            if (!input.hasNextLine()) return;
            String question = input.nextLine();
            if (question.equals(":quitter")) return;
            if (question.equals(":nouveau")) { conversation.reset(); continue; }
            if (question.isBlank()) continue;
            output.print("Qwen > ");
            try {
                var result = conversation.ask(question, ignored -> {});
                output.print(result.text());
                output.println();
                if (result.truncated()) output.println("[Limite de sortie configurée atteinte ; réponse native conservée, aucune réécriture.]");
            } catch (RuntimeException error) {
                var failure = new ConversationOutcome<Object>(ConversationOutcome.classify(error), null);
                output.println("[" + failure.status() + "] " + failure.explanation());
            }
        }
    }
    private static void printAgentMetrics(fr.ivorama.cognitive.generation.TurnTelemetry.Snapshot metrics) {
        System.out.printf(Locale.ROOT, "Agent : %d appels ; tokens connus %d/%d ; %.2f s ; comptage complet : %s%n",
                metrics.calls().size(), metrics.knownInputTokens(), metrics.knownOutputTokens(), metrics.totalNanos()/1e9, metrics.completeTokenAccounting());
    }

}
