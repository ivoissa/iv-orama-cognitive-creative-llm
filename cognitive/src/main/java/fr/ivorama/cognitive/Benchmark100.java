package fr.ivorama.cognitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * IVOC-EVAL-001 : évaluateur verrouillé, sans réglage des règles d'interprétation.
 * Le corpus externe de 100 scénarios ne doit pas être utilisé pour développer le modèle.
 * TSV UTF-8 à sept colonnes : id, entités, prémisses, question, attendu_initial,
 * actions_après_question, attendu_final. Aucun score si les 100 scénarios sont absents.
 */
public final class Benchmark100 {
    private Benchmark100() {}
    public enum Expected { YES, NO, UNKNOWN, NEED_INFORMATION, INCONSISTENT, RESOURCE_LIMIT, REJECT }
    public record Score(int total, int correctInitial, int correctFinal, int unjustifiedClosures,
                        String fileSha256, List<String> failedIds) {}
    public static Score evaluate(Path tsv) throws IOException {
        byte[] bytes=Files.readAllBytes(tsv);
        List<String> lines=Files.readAllLines(tsv,StandardCharsets.UTF_8);
        List<String[]> cases=new ArrayList<>();
        Set<String> ids=new LinkedHashSet<>();
        Set<String> uniqueScenarios=new HashSet<>();
        for(String line:lines) {
            if(line.isBlank() || line.startsWith("#")) continue;
            String[] fields=line.split("\\t",-1);
            if(fields.length!=7) throw new IllegalArgumentException("TSV : exactement sept colonnes requises");
            if(!fields[0].matches("[A-Za-z0-9_-]+") || !ids.add(fields[0]))
                throw new IllegalArgumentException("Identifiant de cas invalide/dupliqué : "+fields[0]);
            Expected.valueOf(fields[4]); Expected.valueOf(fields[6]);
            if(!uniqueScenarios.add(String.join("\t",Arrays.copyOfRange(fields,1,fields.length))))
                throw new IllegalArgumentException("Scénario identique dupliqué : "+fields[0]);
            cases.add(fields);
        }
        if(cases.size()!=100) throw new IllegalArgumentException("Corpus indépendant : exactement 100 cas requis, reçu "+cases.size());
        int initial=0, end=0, unjustified=0;
        List<String> failed=new ArrayList<>();
        for(String[] c:cases) {
            var d=new FrenchQuestionDialogue();
            String[] entityTexts=split(c[1]);
            for(String entityText:entityTexts) {
                String[] parts=entityText.split("\\s+");
                if(parts.length!=2) throw new IllegalArgumentException("Entité invalide dans "+c[0]);
                d.declare(parts[0],parts[1]);
            }
            for(String sentence:split(c[2])) d.teach(sentence);
            Expected measuredInitial;
            String questionId=null;
            boolean grammarRejected=false;
            try {
                if(d.language().interpret(c[3]).act()!=ControlledFrench.Act.ASK)
                    grammarRejected=true;
            } catch (GrammarValidationException ex) {grammarRejected=true;}
            if(grammarRejected) measuredInitial=Expected.REJECT;
            else {
                var r=d.ask(c[3]); // une panne du moteur ne doit pas être notée comme REJECT correct
                measuredInitial=classify(r.report());
                questionId=r.questionId();
            }
            Expected expectedInitial=Expected.valueOf(c[4]);
            if(measuredInitial==expectedInitial) initial++; else failed.add(c[0]+":initial attendu="+expectedInitial+" obtenu="+measuredInitial);
            Expected measuredFinal=measuredInitial;
            {
                for(String action:split(c[5])) {
                    if(questionId==null) throw new IllegalArgumentException("Action impossible après rejet");
                    String a=action.toLowerCase(Locale.ROOT);
                    if(a.equals("observe oui") || a.equals("observe non"))
                        d.answerObservation(questionId,a.endsWith("oui"),"Déclaration simulée du banc");
                    else if(a.startsWith("retire ")) d.retract(action.substring(7).trim());
                    else if(a.equals("ignore")) d.decline(questionId);
                    else throw new IllegalArgumentException("Action de benchmark inconnue : "+action);
                }
                if(questionId!=null) measuredFinal=classify(d.status(questionId).report());
            } // Les erreurs de scénario et pannes remontent : aucun score REJECT artificiel.
            Expected expectedFinal=Expected.valueOf(c[6]);
            if(measuredFinal==expectedFinal) end++; else failed.add(c[0]+":final attendu="+expectedFinal+" obtenu="+measuredFinal);
            if ((measuredInitial==Expected.YES||measuredInitial==Expected.NO)
                    && measuredInitial!=expectedInitial) unjustified++;
            if ((measuredFinal==Expected.YES||measuredFinal==Expected.NO)
                    && measuredFinal!=expectedFinal) unjustified++;
        }
        return new Score(cases.size(),initial,end,unjustified,sha256(bytes),List.copyOf(failed));
    }
    private static Expected classify(IntegratedQuestionCycle.Report r) {
        return switch(r.status()) {
            case RESTORED_CONDITIONALLY,RESTORED_FORMALLY,RESTORED_JUSTIFIED ->
                    r.answer().orElseThrow() ? Expected.YES : Expected.NO;
            case NEED_INFORMATION -> Expected.NEED_INFORMATION;
            case OPEN -> Expected.UNKNOWN;
            case INCONSISTENT -> Expected.INCONSISTENT;
            case RESOURCE_LIMIT -> Expected.RESOURCE_LIMIT;
        };
    }
    private static String[] split(String s) {
        return s.isBlank()?new String[0]:Arrays.stream(s.split("\\s*;\\s*"))
                .filter(x->!x.isBlank()).toArray(String[]::new);
    }
    private static String sha256(byte[] bytes) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch(NoSuchAlgorithmException ex) {throw new IllegalStateException(ex);}
    }
    public static void main(String[] args) throws IOException {
        if(args.length!=1) throw new IllegalArgumentException("Usage: java ... Benchmark100 <external-100.tsv>");
        Score s=evaluate(Path.of(args[0]));
        System.out.println("Corpus SHA-256="+s.fileSha256());
        System.out.println("100 cas : réponses initiales="+s.correctInitial()+"/100, finales="+s.correctFinal()
                +"/100 ; clôtures injustifiées selon l'oracle="+s.unjustifiedClosures());
        for(String failed:s.failedIds()) System.out.println("ECHEC "+failed);
    }
}
