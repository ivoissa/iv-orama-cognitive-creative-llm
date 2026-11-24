package fr.ivorama.cognitive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Mode 4 : saisie interactive en français, avec ajouts/rétractions et sauvegarde. */
public final class OntologicalConsole {
    private OntologicalConsole() {}
    public static void run(InputStream in, PrintStream out) { runInternal(in,out,false); }
    public static void runWeb(InputStream in, PrintStream out) { runInternal(in,out,true); }
    private static void runInternal(InputStream in, PrintStream out, boolean web) {
        Path storage = Path.of("ivorama-knowledge.tsv");
        OntologicalKnowledgeGraph graph;
        try { graph = Files.exists(storage) ? OntologicalGraphStore.load(storage) : OntologicalDemoFixture.create(); }
        catch (IOException ex) {
            out.println("Chargement impossible : "+ex.getMessage()+". Aucune donnée écrasée."); return;
        }
        IntegratedQuestionCycle cycle = web ? new IntegratedQuestionCycle() : null;
        WebSource.Provider provider = null;
        QuestionOrientationDialogue conversation = null;
        if (web) {
            String key = System.getenv("BRAVE_SEARCH_API_KEY");
            provider = (key == null || key.isBlank()) ? new WebSource.Wikipedia() : new WebSource.Brave(key);
            conversation = new QuestionOrientationDialogue(graph,cycle,provider,java.time.Clock.system(java.time.ZoneId.of(System.getProperty("ivorama.zone","Europe/Paris"))));
        }
        FrenchReasoningRouter frenchStructured=web?new FrenchReasoningRouter(cycle):null;
        out.println("IV-ORAMA " + (web ? "0.6.34" : "0.6.19") + (web ? " — orientation des questions" : " — questions ontologiques") + " (source DEMO non vérifiée par défaut)");
        if (web) out.println("Orientation activée : raisonnement local, précision du lieu/date, observation ou recherche documentaire.");
        out.println("Exemple : Le Soleil est-il un astre ? | Mercure est-il un métal ?");
        out.println("Commandes : :aide, :liste, :sauve, :quitter");
        Scanner sc = new Scanner(in, StandardCharsets.UTF_8);
        while (true) {
            out.print("iv-orama-ontology> "); out.flush();
            if (!sc.hasNextLine()) break;
            String line = sc.nextLine().strip();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase(":quitter") || line.equalsIgnoreCase("quitter")) break;
            if (line.equals(":aide")) { help(out); continue; }
            if (web && line.equals(":questions")) {
                out.println("Actes FCI : " + cycle.ontologyQuestions()); continue;
            }
            if (web && line.startsWith(":revoir ")) {
                try { String id=line.substring(8).strip();out.println((id.startsWith("T")||id.startsWith("L"))?frenchStructured.revisitTextTask(id).describe():frenchStructured.hasQuestion(id)?frenchStructured.revisit(id).describe():conversation.revisit(id).describe()); }
                catch (IllegalArgumentException ex) { out.println("[ERREUR] " + ex.getMessage()); }
                continue;
            }
            if (line.equals(":liste")) {
                out.println("Concepts : "+graph.nodes());
                out.println("Sources : "+graph.sources().all());
                out.println("Assertions : "+graph.history()); continue;
            }
            if (line.equals(":sauve")) {
                try { OntologicalGraphStore.save(graph,storage); out.println("Sauvegardé : "+storage.toAbsolutePath()); }
                catch (IOException ex) { out.println("ERREUR sauvegarde : "+ex.getMessage()); }
                continue;
            }
            try {
                if(web&&line.equals(":taches")){out.println("Tâches documentaires : "+frenchStructured.textTaskIds());continue;}
                if(web&&(line.startsWith(":exclure T")||line.startsWith(":exclure L"))){String[] parts=line.split("\\s+",3);if(parts.length!=3)throw new IllegalArgumentException("Usage : :exclure Tn URL");out.println(frenchStructured.excludeTextSource(parts[1],java.net.URI.create(parts[2])).describe());continue;}
                if (line.startsWith(":")) {
                    boolean changed = apply(line,graph);
                    if (changed) {
                        OntologicalGraphStore.save(graph,storage);
                        out.println("Mise à jour sauvegardée : "+storage.toAbsolutePath());
                    }
                } else if (web) {
                    var translation=new FrenchStructuredTranslator().translate(line);
                    if(DirectiveActs.classify(line).isPresent() || WikipediaQuestion.isCandidate(line) || (translation.complete() && !translation.problem().orElseThrow().premises().isEmpty()) || new FrenchEventTranslator().looksNarrative(line)){conversation.clearContext();out.println(frenchStructured.respond(line).describe());}
                    else out.println(conversation.respond(line).describe());
                }
                else out.println(new OntologicalDialogue(graph).ask(line));
            } catch (IllegalArgumentException | IOException ex) {
                out.println("[ERREUR] "+ex.getMessage());
            }
        }
    }
    private static boolean apply(String command, OntologicalKnowledgeGraph g) {
        int sp = command.indexOf(' ');
        String operation = sp == -1 ? command : command.substring(0,sp);
        String value = sp == -1 ? "" : command.substring(sp+1).strip();
        String[] fields = value.split("\\s*\\|\\s*",-1);
        switch (operation) {
            case ":concept" -> {
                require(fields,3,operation);
                ConceptNode.Kind kind = switch (fields[1].strip().toLowerCase(Locale.ROOT)) {
                    case "individu" -> ConceptNode.Kind.INDIVIDUAL;
                    case "classe" -> ConceptNode.Kind.CLASS;
                    default -> throw new IllegalArgumentException("Utilise individu ou classe");
                };
                g.addNode(new ConceptNode(fields[0].strip(), fields[2],kind));
            }
            case ":source" -> {
                require(fields,3,operation);
                g.sources().add(new KnowledgeSourceRegistry.Source(fields[0].strip(),fields[1],fields[2],
                        KnowledgeSourceRegistry.Status.UNVERIFIED));
            }
            case ":alias" -> { require(fields,2,operation); g.alias(fields[0],fields[1].strip()); }
            case ":fait" -> {
                require(fields,6,operation);
                if (!fields[4].equals("+") && !fields[4].equals("-"))
                    throw new IllegalArgumentException("Signe attendu: + ou -");
                g.addAssertion(new RelationAssertion(fields[0].strip(),fields[1].strip(),
                        RelationAssertion.Predicate.valueOf(fields[2].strip().toUpperCase(Locale.ROOT)),
                        fields[3].strip(),fields[4].equals("+"),fields[5].strip()));
            }
            case ":retire" -> g.retract(value);
            case ":revoque" -> g.sources().setStatus(value,KnowledgeSourceRegistry.Status.REVOKED);
            case ":verifie" -> g.sources().setStatus(value,KnowledgeSourceRegistry.Status.HUMAN_CHECKED);
            default -> throw new IllegalArgumentException("Commande inconnue. :aide pour la syntaxe");
        }
        return true;
    }
    private static void require(String[] args,int count,String op) {
        if (args.length != count || Arrays.stream(args).anyMatch(String::isBlank))
            throw new IllegalArgumentException("Arguments : "+op+" ("+count+" champs séparés par |)");
    }
    private static void help(PrintStream o) {
        o.println("Question : Le Soleil est-il un astre ? (formes interrogatives simples seulement)");
        o.println(":concept id | individu|classe | Libellé");
        o.println(":source id | Description | référence (enregistrée NON VÉRIFIÉE)");
        o.println(":alias expression | concept_id");
        o.println(":fait id | sujet_id | INSTANCE_OF|SUBCLASS_OF | objet_id | +|- | source_id");
        o.println(":retire id_assertion | :revoque id_source | :verifie id_source (déclaration humaine)");
        o.println(":liste | :sauve | :quitter");
        o.println("Mode --ontology-web : questions simples, Pourquoi ?, météo datée, états individuels ; :questions | :revoir Q1.");
        o.println("Les mutations sont automatiquement enregistrées dans ivorama-knowledge.tsv.");
    }
}
