package fr.ivorama.cognitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * IVOC-DEV-100 : inventaire de 100 compétences et évaluation répétable des seuls
 * scénarios représentables dans le DSL. N'est PAS un examen indépendant.
 */
public final class Development100 {
    private Development100() {}
    private static final List<String> FAMILIES = List.of("DEDUCTION", "INCERTITUDE", "DIALOGUE", "FRANCAIS", "TRANSFERT");
    public record Case(String id, String family, String objective, String question,
                       String scope, String result, String observation) {}
    public record Report(List<Case> cases, int runnable, int passed, int failed, int gaps,
                         String sourceHash, String activationHash, Set<String> activatedIds,
                         IndependentEvaluation100.Result evaluator) {
        public Report { cases=List.copyOf(cases); activatedIds=Set.copyOf(activatedIds); }
        public String summary() {return "Total="+cases.size()+" PASS="+passed+" FAIL="+failed+
            " NON_PRIS_EN_CHARGE="+gaps+" ; evaluables="+runnable+" ; empreinte catalogue="+sourceHash+" ; empreinte activation="+activationHash;}
    }
    public static Report evaluate(Path catalog) throws IOException {
        byte[] content=Files.readAllBytes(catalog);
        String hash;
        try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));}
        catch(Exception ex) {throw new IllegalStateException("SHA-256 manquant",ex);}
        var all=new ArrayList<String[]>();
        var executable=new ArrayList<String>();
        Path activationFile=catalog.getParent().resolve("ACTIVATIONS_069.tsv");
        Set<String> activated=new LinkedHashSet<>();
        String activationHash="aucune";
        if(Files.isRegularFile(activationFile)) {
            byte[] selection=Files.readAllBytes(activationFile);
            try {activationHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(selection));}
            catch(Exception ex) {throw new IllegalStateException(ex);}
            List<String> rows=new String(selection,StandardCharsets.UTF_8).lines().toList();
            if(rows.isEmpty()||!rows.get(0).equals("id\tmotif"))
                throw new IllegalArgumentException("Format des activations invalide");
            for(String row:rows.subList(1,rows.size())) {
                if(row.isBlank())continue;
                String[] fields=row.split("\t",-1);
                if(fields.length!=2||fields[1].isBlank()||!activated.add(fields[0]))
                    throw new IllegalArgumentException("Activation invalide ou dupliquée : "+row);
            }
        }
        var ids=new HashSet<String>();
        Map<String,Integer> groups=new HashMap<>();
        for(String line:new String(content,StandardCharsets.UTF_8).lines().skip(1).toList()) {
            if(line.isBlank())continue;
            String[] f=line.split("\t",-1);
            if(f.length!=14)throw new IllegalArgumentException("Catalogue à 14 colonnes : "+line);
            if(!f[0].matches("(?:DED|INC|DIA|FRA|TRA)-[0-9]{2}") || !ids.add(f[0]))
                throw new IllegalArgumentException("ID invalide ou dupliqué : "+f[0]);
            if(!FAMILIES.contains(f[1]) || f[2].isBlank() || f[5].isBlank())
                throw new IllegalArgumentException("Compétence ou question absente : "+f[0]);
            if(!f[12].equals("RUN") && !f[12].equals("GAP"))
                throw new IllegalArgumentException("État attendu RUN/GAP : "+f[0]);
            if(f[12].equals("GAP") && f[13].isBlank())
                throw new IllegalArgumentException("Motif de non-prise en charge requis : "+f[0]);
            all.add(f);groups.merge(f[1],1,Integer::sum);
            if(f[12].equals("RUN") || activated.contains(f[0])) {
                String[] c={f[0],f[1],f[3],f[4],f[5],f[6],f[7],f[8],f[9],f[10],f[11]};
                executable.add(String.join("\t",c));
            }
        }
        for(String id:activated) if(all.stream().noneMatch(f->f[0].equals(id)&&f[12].equals("GAP")))
            throw new IllegalArgumentException("Une activation doit cibler une lacune GAP existante : "+id);
        if(all.size()!=100 || FAMILIES.stream().anyMatch(x->groups.getOrDefault(x,0)!=20))
            throw new IllegalArgumentException("100 lignes exactement et 20 par famille obligatoires");
        if(executable.isEmpty())throw new IllegalArgumentException("Aucune évaluation réelle disponible");
        Path temp=Files.createTempFile("ivoc-development-100-", ".tsv");
        IndependentEvaluation100.Result evaluated;
        try {
            Files.write(temp,executable,StandardCharsets.UTF_8);
            evaluated=IndependentEvaluation100.evaluateDevelopment(temp);
        } finally {Files.deleteIfExists(temp);}
        Set<String> failures=new HashSet<>();
        for(String error:evaluated.failures()) {
            String id=error.split(" : ",2)[0];failures.add(id);
        }
        List<Case> cases=new ArrayList<>();
        int pass=0,fail=0,gap=0;
        for(String[] f:all) {
            String state;
            if(f[12].equals("GAP")&&!activated.contains(f[0])) {state="NON_PRIS_EN_CHARGE";gap++;}
            else if(failures.contains(f[0])){state="FAIL";fail++;}
            else {state="PASS";pass++;}
            String observation=f[13];
            if(activated.contains(f[0])) observation="Activation 0.6.9 : "+f[2]+" | "+observation;
            if(state.equals("FAIL")) {
                for(String error:evaluated.failures())if(error.startsWith(f[0]+" : ")) {
                    observation += " | " + error.substring(f[0].length()+3);break;
                }
            }
            cases.add(new Case(f[0],f[1],f[2],f[5],state,f[10],observation));
        }
        if(pass!=evaluated.complete() || pass+fail!=executable.size() ||
           evaluated.total()!=executable.size())
            throw new IllegalStateException("Incohérence de comptage; résultat non publié");
        // Le catalogue reste immuable : les nouvelles capacités ont un manifeste séparé.
        return new Report(cases,executable.size(),pass,fail,gap,hash,activationHash,activated,evaluated);
    }
    public static String asMarkdown(Report r) {
        StringBuilder b=new StringBuilder("# IV-ORAMA Cognitive — Rapport DEV-100 (NON aveugle)\n\n");
        b.append("**").append(r.summary()).append("**\n\n");
        b.append("**Activations hors catalogue :** ").append(r.activatedIds()).append(". Empreinte : ")
         .append(r.activationHash()).append(". Une capacité activée compte comme FAIL si elle échoue; ")
         .append("aucune activation par sélection des succès.\n\n");
        b.append("Ces 100 scénarios sont écrits pendant le développement, sans prétention d'indépendance. ")
         .append("Les cas non représentables restent dans le dénominateur. Un PASS est une conformité ")
         .append("au cas spécifié, pas une preuve d'intelligence générale.\n\n");
        b.append("## Par famille\n\n| Famille | PASS | FAIL | NON PRIS EN CHARGE | Total |\n|---|---:|---:|---:|---:|\n");
        for(String family:FAMILIES) {
            int p=0,f=0,g=0;
            for(Case c:r.cases())if(c.family().equals(family))switch(c.scope()) {
                case "PASS" -> p++;
                case "FAIL" -> f++;
                default -> g++;
            };
            b.append("| ").append(family).append(" | ").append(p).append(" | ").append(f)
             .append(" | ").append(g).append(" | 20 |\n");
        }
        b.append("\n## Mesures complémentaires (cas exécutables uniquement)\n\n")
         .append("- Sens formel contrôlé : ").append(r.evaluator().semantics()).append("/")
         .append(r.evaluator().semanticEvaluated()).append(" ; non évalué : ")
         .append(r.evaluator().semanticNotAssessed()).append(" ; cas interrompus avant cette vérification : ")
         .append(r.runnable()-r.evaluator().semanticEvaluated()-r.evaluator().semanticNotAssessed())
         .append(".\n")
         .append("- Types FCI conformes : ").append(r.evaluator().acts()).append("/")
         .append(r.runnable()).append(".\n")
         .append("- Réponses initiales conformes : ").append(r.evaluator().initial()).append("/")
         .append(r.runnable()).append(" ; réponses finales conformes : ")
         .append(r.evaluator().finalAnswers()).append("/").append(r.runnable()).append(".\n")
         .append("- Clôtures suspectes (heuristique du correcteur) : ")
         .append(r.evaluator().unjustifiedClosures()).append(".\n\n")
         .append("Une formule d'oracle absente (`-`) correspond à une sémantique **non évaluée**, pas à une interprétation réussie.\n\n")
         .append("## Détail des 100 scénarios\n\n");
        for(Case c:r.cases())b.append("### ").append(c.id()).append(" — ").append(c.scope())
          .append("\n\n**Compétence :** ").append(c.objective())
          .append("\n\n**Question :** ").append(c.question())
          .append("\n\n**Résultat de référence final :** ").append(c.result())
          .append("\n\n**Observation :** ").append(c.observation()).append("\n\n");
        return b.toString();
    }
    public static void main(String[] args) throws IOException {
        if(args.length!=1 && args.length!=2)throw new IllegalArgumentException(
            "Usage : Development100 <SCENARIOS_100_DEVELOPPEMENT.tsv> [RAPPORT.md]");
        Report r=evaluate(Path.of(args[0]));
        System.out.println("IVOC DEV-100 : "+r.summary());
        for(Case c:r.cases())if(c.scope().equals("FAIL"))
            System.out.println("ÉCHEC "+c.id()+" : "+c.observation());
        if(args.length==2)Files.writeString(Path.of(args[1]),asMarkdown(r),StandardCharsets.UTF_8);
    }
}
