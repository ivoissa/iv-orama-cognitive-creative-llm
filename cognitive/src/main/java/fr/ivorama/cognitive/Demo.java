package fr.ivorama.cognitive;

import java.util.*;

/** Noyau symbolique ; annotations NLP statistiques séparées et accès documentaire explicite. */
public final class Demo {
    private Demo() {}
    // IntelliJ: edit these defaults or supply program arguments. Explicit arguments take precedence.
    private static final String[] DEFAULT_ARGUMENTS = {"--local-chat"};
    public static void main(String[] args) {
        if (args.length == 0) args = DEFAULT_ARGUMENTS.clone();
        if (args.length >= 4 && args[0].equals("--agent-reason-offline")) {
            try { fr.ivorama.cognitive.agent.ReasonedAgentCli.main(Arrays.copyOfRange(args, 1, args.length)); }
            catch (Exception ex) { throw new IllegalStateException("Enquête hors ligne impossible", ex); }
            return;
        }
        if((args.length==1 && (args[0].equals("--local-chat")||args[0].equals("--local-chat-iv")||args[0].equals("--local-check")))
                || (args.length>=3 && (args[0].equals("--local-observe-reasoned")||args[0].equals("--local-observe")||args[0].equals("--local-propose")||args[0].equals("--local-propose-validate")))){
            try{
                var cli=Class.forName("fr.ivorama.cognitive.generative.LocalAssistantCli");
                String[] forwarded;
                if(args[0].equals("--local-observe-reasoned")||args[0].equals("--local-observe")||args[0].equals("--local-propose")||args[0].equals("--local-propose-validate")){
                    forwarded=args.clone();forwarded[0]=args[0].equals("--local-observe-reasoned")?"--observe-reasoned":args[0].equals("--local-propose-validate")?"--propose-validate":args[0].equals("--local-propose")?"--propose":"--observe";
                } else forwarded=new String[]{args[0].equals("--local-check")?"--check":args[0].equals("--local-chat-iv")?"--chat-iv":"--chat"};
                cli.getMethod("main",String[].class).invoke(null,(Object)forwarded);
            }catch(ClassNotFoundException ex){throw new IllegalStateException("Compiler avec mvn -Plocal-llm verify pour activer le dialogue local",ex);}
            catch(java.lang.reflect.InvocationTargetException ex){throw new IllegalStateException("Dialogue local indisponible : "+ex.getCause().getMessage(),ex.getCause());}
            catch(ReflectiveOperationException ex){throw new IllegalStateException("Chargement du dialogue local impossible",ex);}
            return;
        }
        if(args.length==3 && args[0].equals("--wikidata")){
            var wd=classificationService(new IntegratedQuestionCycle());System.out.println(wd.classify(args[1],args[2]).describe());return;
        }
        if(args.length==1 && args[0].equals("--reason-fr")){
            var cycle=new IntegratedQuestionCycle();var service=new FrenchReasoningRouter(cycle);
            var classifications=classificationService(cycle);
            var scanner=new java.util.Scanner(System.in,java.nio.charset.StandardCharsets.UTF_8);
            System.out.println("IV-ORAMA 0.7.8-candidate — français vers JSON vers raisonnement (fragment contrôlé)");
            while(scanner.hasNextLine()){
                String line=scanner.nextLine().strip();if(line.equals(":quitter"))break;if(line.isBlank())continue;
                try{
                 if(line.startsWith(":classer ")){String[] terms=line.substring(9).split("\\|",-1);if(terms.length!=2)throw new IllegalArgumentException("Usage : :classer sujet | cible");System.out.println(classifications.classify(terms[0].strip(),terms[1].strip()).describe());}
                 else if(line.startsWith(":classer-revoir "))System.out.println(classifications.revisit(line.substring(16).strip()).describe());
                 else if(line.startsWith(":classer-exclure ")){String[] parts=line.split("\\s+",3);if(parts.length!=3)throw new IllegalArgumentException("Usage : :classer-exclure Qn déclaration");System.out.println(classifications.exclude(parts[1],parts[2]).describe());}
                 else if(line.equals(":taches"))System.out.println("Tâches documentaires : "+service.textTaskIds());
                 else if((line.startsWith(":revoir T")||line.startsWith(":revoir L")))System.out.println(service.revisitTextTask(line.substring(8).strip()).describe());
                 else if((line.startsWith(":exclure T")||line.startsWith(":exclure L"))){String[] parts=line.split("\\s+",3);if(parts.length!=3)throw new IllegalArgumentException("Usage : :exclure Tn URL");System.out.println(service.excludeTextSource(parts[1],java.net.URI.create(parts[2])).describe());}
                 else System.out.println(service.respond(line).describe());
                }catch(IllegalArgumentException ex){System.out.println("Entrée rejetée : "+ex.getMessage());}
            }
            return;
        }
        if(args.length==2 && args[0].equals("--wikipedia")){
            System.out.println(new WikipediaDefinitionService(new IntegratedQuestionCycle(),new WebSource.Wikipedia()).search(args[1]).describe());return;
        }
        if(args.length==2 && (args[0].equals("--structured") || args[0].equals("--structured-web"))){
            try(var input=java.nio.file.Files.newInputStream(java.nio.file.Path.of(args[1]))){
                byte[] bytes=input.readNBytes(65537);
                if(bytes.length>65536)throw new IllegalArgumentException("JSON limité à 64 Kio");
                String json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
                if(args[0].equals("--structured-web")){
                    String key=System.getenv("BRAVE_SEARCH_API_KEY");
                    WebSource.Provider provider=key==null||key.isBlank()?new WebSource.Wikipedia():new WebSource.Brave(key);
                    System.out.println(new StructuredWebReasoningService(new IntegratedQuestionCycle(),provider).askJson(json).describe());
                }else System.out.println(new StructuredReasoningService(new IntegratedQuestionCycle()).askJson(json).describe());
            }catch(java.io.IOException|IllegalArgumentException ex){System.err.println("Entrée structurée rejetée : "+ex.getMessage());System.exit(2);}
            return;
        }
        if (args.length == 1 && args[0].equals("--ontology-web")) {
            OntologicalConsole.runWeb(System.in, System.out);
            return;
        }
        if (args.length == 1 && args[0].equals("--ontology")) {
            OntologicalConsole.run(System.in, System.out);
            return;
        }
        if (args.length == 1 && args[0].equals("--interactive")) {
            IVOramaInteractiveMain.main(new String[0]);
            return;
        }
        if (args.length == 1 && args[0].equals("--compose")) {
            CompositionalFrenchDialogue.run(System.in,System.out);
            return;
        }
        if (args.length == 1 && args[0].equals("--assistant")) {
            StandaloneTechnicalAssistant.run(System.in, System.out);
            return;
        }
        if (args.length == 1 && args[0].equals("--francais")) {
            FrenchConsole.run(System.in, System.out);
            return;
        }
        if (args.length == 1 && args[0].equals("--dialogue")) {
            DialogueConsole.run(System.in, System.out);
            return;
        }
        if (args.length != 1 || !args[0].equals("--core-demo")) throw new IllegalArgumentException("Mode inconnu. Utiliser --local-chat, --local-chat-iv ou un mode explicite documenté.");
        System.out.println("=== IV-ORAMA Cognitive Core 0.6.19 (historique FCI/RDO) ===");
        Formula p = Formula.atom("p"), q = Formula.atom("q");
        var stable = InterrogativeUniverse.stable(Set.of(q));
        var posed = stable.ask(new InterrogativeUniverse.QuestionAct("Q-1",
                InterrogativeUniverse.Origin.HUMAN, QuestionGap.Question.of(q)));
        System.out.println("FCI-002 avant question : " + stable.snapshot(ModelExplorer.Budget.small()).phase());
        System.out.println("FCI-002 question posee (reponse deja connue) : "
                + posed.snapshot(ModelExplorer.Budget.small()).phase());
        var restored = posed.respond(new InterrogativeUniverse.AnswerAct(Map.of("question", true),
                QuestionGap.Response.empty(), "reponse a partir des connaissances"));
        System.out.println("FCI-002 reponse conditionnelle : " + restored.snapshot(ModelExplorer.Budget.small()).phase());
        KnowledgeBase memory = new KnowledgeBase();
        memory.addPremise("A1", p, "assertion de démonstration", KnowledgeBase.Kind.ASSERTION);
        memory.addPremise("A2", Formula.implies(p, q), "assertion de démonstration", KnowledgeBase.Kind.ASSERTION);
        Proof proof = ProofSearch.find(memory.activePremises(), q, 100).orElseThrow();
        memory.recordProof("C1", proof);
        System.out.println("Déduction q : " + memory.supportedConclusion("C1")
                + " (preuve valide = " + memory.isProofActive("C1") + ")");
        Quotation quote = new Quotation(0, Formula.implies(p, q));
        System.out.println("Citation : niveau " + quote.sourceLevel() + " -> " + quote.targetLevel()
                + ", ne crée aucune vérité");
        memory.retractPremise("A1");
        System.out.println("Après retrait de p : conclusion q justifiée = " + memory.isProofActive("C1"));

        Formula a = Formula.atom("Pa"), b = Formula.atom("Pb"), c = Formula.atom("Pc");
        Set<Formula> knowledge = Set.of(a, Formula.iff(b, c));
        RdoPlanner.Plan plan = RdoPlanner.propose(knowledge, c,
                List.of(new RdoPlanner.Candidate(b, 1), new RdoPlanner.Candidate(a, 1)),
                ModelExplorer.Budget.small());
        System.out.println("Question Pc avant observation : " + plan.analysis().status());
        System.out.println("Observation recommandée : " + plan.choice()
                .map(ch -> ch.candidate().observation().toString()).orElse("aucune"));
        // Observation simulée, pas réalisée par un capteur réel.
        Set<Formula> observed = new HashSet<>(knowledge);
        observed.add(b);
        ModelExplorer.Analysis after = ModelExplorer.analyze(observed, c, ModelExplorer.Budget.small());
        Proof justified = ProofSearch.find(observed, c, 100).orElseThrow();
        System.out.println("Après observation SIMULÉE Pb=true : " + after.status());
        System.out.println("Preuve Pc validée : " + ProofChecker.verify(justified, observed).valid());
        // FCI: question as a gap, answer as conditional repair.
        QuestionGap.Question question = new QuestionGap.Question(List.of(
                new QuestionGap.Facet("Pc", c),
                new QuestionGap.Facet("Pa", a)));
        QuestionGap.Report before = QuestionGap.assess(knowledge, question,
                QuestionGap.Response.empty(), ModelExplorer.Budget.small());
        QuestionGap.Response answer = new QuestionGap.Response(List.of(
                new QuestionGap.Claim(b, "Observation SIMULÉE et explicitement admise",
                        QuestionGap.Admission.ADMITTED)));
        QuestionGap.Report repaired = QuestionGap.assess(knowledge, question,
                answer, ModelExplorer.Budget.small());
        System.out.printf(Locale.ROOT,"FCI-001 HISTORIQUE : couverture de facettes %.0f%% -> %.0f%% (%s)%n",
                before.closure()*100, repaired.closure()*100, repaired.status());
        QuestionSemantics.Assessment semantic = QuestionSemantics.assess(knowledge, question,
                answer, ModelExplorer.Budget.small());
        System.out.printf(Locale.ROOT,
                "FCI-003 : reponses possibles %d -> %d ; gain relatif a Q = %.3f bits ; determine = %s%n",
                semantic.possibleAnswersBefore(), semantic.possibleAnswersAfter(),
                semantic.answerGainBits(), semantic.questionDetermined());
        System.out.println("Attention : acceptation d'une prémisse ≠ vérité empirique certifiée");
        System.out.println("--- IVOC-FND-004 : question suivie entre plusieurs tours ---");
        IntegratedQuestionCycle cycle = new IntegratedQuestionCycle();
        cycle.teach("Pb -> Pc", "hypothèse démo");
        cycle.teach("Pc -> Pb", "hypothèse démo");
        cycle.registerObservable("Pb", 1);
        var interrogative = cycle.ask("Pc");
        System.out.println(interrogative.id() + " : " + interrogative.status()
                + ", question complémentaire = " + interrogative.requestedObservation().orElse(null));
        var completed = cycle.answerObservation(interrogative.id(), true, "déclaration démo non vérifiée");
        System.out.println(completed.id() + " : " + completed.status() + ", réponse = " + completed.answer());
        String observationId = cycle.premises().get(cycle.premises().size() - 1).id();
        cycle.retract(observationId);
        System.out.println("Retrait " + observationId + " : " + cycle.question(interrogative.id()).status()
                + ", rouverte = " + cycle.question(interrogative.id()).reopened());
        System.out.println("Paramètres numériques appris : 0");
    }
    private static WikidataClassificationService classificationService(IntegratedQuestionCycle cycle){
        return new WikidataClassificationService(cycle,new WikidataHttpSource(java.nio.file.Path.of(System.getProperty("ivorama.wikidata.cache",".ivorama/wikidata-v1")),Boolean.getBoolean("ivorama.wikidata.offline")));
    }
}
