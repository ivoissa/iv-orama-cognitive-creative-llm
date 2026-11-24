package fr.ivorama.cognitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** IVOC-EVAL-003 : vérification préalable NON exécutante des 100 questions externes.
 * Elle ne certifie PAS l'indépendance des auteurs, qui nécessite un audit extérieur.
 * Aucun résultat du moteur IV-ORAMA n'est accessible pendant le pré-contrôle.
 */
public final class ExternalCorpusPreflight {
    private ExternalCorpusPreflight() {}
    public record Finding(String id,String code,String explanation) {}
    public record Report(String sha256,int cases,Map<String,Integer> byFamily,
                         List<Finding> errors,List<Finding> warnings) {
        public Report {byFamily=Map.copyOf(byFamily);errors=List.copyOf(errors);warnings=List.copyOf(warnings);}
        public boolean readyForIndependentReview() {return errors.isEmpty() && cases==100;}
    }
    private static final Set<String> FAMILIES=Set.of("DEDUCTION","INCERTITUDE","DIALOGUE","FRANCAIS","TRANSFERT");
    private static final Set<String> STATES=Set.of("INTERPRETED","NEEDS_CLARIFICATION","UNINTERPRETED",
        "EXPLANATION","INVESTIGATION","TEMPORAL","WITHDRAWN","PARTITION");
    private static final Set<String> OUTCOMES=Set.of("YES","NO","UNKNOWN","NEED_INFORMATION","INCONSISTENT",
        "RESOURCE_LIMIT","CLARIFICATION","UNINTERPRETED","WHY_PROVEN","WHY_UNPROVEN",
        "INVESTIGATION_PLAN","INVESTIGATION_UNAVAILABLE","TEMPORAL_YES","TEMPORAL_NO",
        "TEMPORAL_UNKNOWN","CAUSAL_UNPROVEN","WITHDRAWN");
    private static final Set<String> EVIDENCE=Set.of("ANY","CHECKED_PROOF","NO_FALSE_CLOSURE","CONDITIONAL_ONLY");
    private static final Pattern IDENT=Pattern.compile("[A-Za-z0-9_-]+");
    private static String norm(String s) {
        String n=Normalizer.normalize(s.toLowerCase(Locale.ROOT),Normalizer.Form.NFD)
            .replaceAll("\\p{M}+","").replace('’','\'');
        // Warning only: a heuristic for superficial paraphrases and renamed entities.
        n=n.replaceAll("\\b(t|e|q|d|p)[0-9]+\\b","<id>")
           .replaceAll("\\b[0-9]+\\b","<n>")
           .replaceAll("\\b[ab]\\b","<id>")
           .replaceAll("[?!.,;:]"," ").replaceAll("\\s+"," ").strip();
        return n;
    }
    public static Report analyze(Path corpus) throws IOException {
        byte[] bytes=Files.readAllBytes(corpus);
        String hash;
        try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
        List<Finding> errors=new ArrayList<>(), warnings=new ArrayList<>();
        Map<String,Integer> family=new LinkedHashMap<>();
        Map<String,List<String>> normalized=new LinkedHashMap<>();
        Set<String> ids=new LinkedHashSet<>(),exact=new HashSet<>();
        int count=0;
        for(String line:new String(bytes,StandardCharsets.UTF_8).lines().toList()) {
            if(line.isBlank()||line.startsWith("#"))continue;
            count++;
            String[] f=line.split("\\t",-1);
            String id=f.length>0?f[0]:"LINE_"+count;
            if(f.length!=11){errors.add(new Finding(id,"COLUMNS","11 colonnes, reçu "+f.length));continue;}
            if(!IDENT.matcher(id).matches()||!ids.add(id))errors.add(new Finding(id,"ID","Identifiant invalide ou réutilisé"));
            if(!FAMILIES.contains(f[1])) errors.add(new Finding(id,"FAMILY","Famille inconnue"));
            else family.merge(f[1],1,Integer::sum);
            if(f[4].isBlank()||f[4].equalsIgnoreCase("TBD")||f[4].equalsIgnoreCase("A REMPLIR"))
                errors.add(new Finding(id,"QUESTION","Question vide ou provisoire"));
            if(!STATES.contains(f[5]))errors.add(new Finding(id,"STATE","Type de question inconnu"));
            if(!OUTCOMES.contains(f[7])||!OUTCOMES.contains(f[9]))
                errors.add(new Finding(id,"GOLD","Résultat initial/final inconnu"));
            if(!EVIDENCE.contains(f[10]))errors.add(new Finding(id,"EVIDENCE","Politique de preuve inconnue"));
            if(f[6].equals("-")) {
                if(f[5].equals("INTERPRETED")||f[5].equals("PARTITION"))
                    errors.add(new Finding(id,"MISSING_SEMANTICS","Signification oracle nécessaire pour ce type"));
            } else {
                try{OracleFormula.parse(f[6]);}catch(RuntimeException ex){
                    errors.add(new Finding(id,"ORACLE","Formule oracle illisible"));}
            }
            if(f[7].equals("YES") && f[9].equals("NO") && f[8].isBlank())
                warnings.add(new Finding(id,"RESULT_CHANGE","Résultat modifié sans opération intermédiaire"));
            String fingerprint=String.join("|",f[1],norm(f[4]),f[5],norm(f[3]),norm(f[8]));
            normalized.computeIfAbsent(fingerprint,k->new ArrayList<>()).add(id);
            String raw=String.join("\t",Arrays.copyOfRange(f,2,11));
            if(!exact.add(raw))errors.add(new Finding(id,"DUPLICATE","Scénario identique (sauf id et famille)"));
        }
        if(count!=100)errors.add(new Finding("CORPUS","COUNT","100 scénarios requis, reçu "+count));
        for(String k:FAMILIES)if(family.getOrDefault(k,0)!=20)
            errors.add(new Finding("CORPUS","BALANCE",k+" : "+family.getOrDefault(k,0)+" au lieu de 20"));
        for(var entry:normalized.entrySet())if(entry.getValue().size()>1)
            warnings.add(new Finding(String.join(",",entry.getValue()),"NEAR_DUPLICATE",
                "Même structure lexicale approximative après normalisation : audit humain requis"));
        return new Report(hash,count,family,errors,warnings);
    }
    public static void main(String[] args) throws IOException {
        if(args.length!=1)throw new IllegalArgumentException("Usage : ExternalCorpusPreflight CORPUS_100.tsv");
        Report r=analyze(Path.of(args[0]));
        System.out.println("PRÉ-CONTRÔLE NON EXÉCUTANT : "+r.cases()+" cas ; SHA-256="+r.sha256());
        System.out.println("Familles : "+r.byFamily()+" ; erreurs="+r.errors().size()+" ; avertissements="+r.warnings().size());
        r.errors().forEach(f->System.out.println("ERREUR "+f.id()+" "+f.code()+" : "+f.explanation()));
        r.warnings().forEach(f->System.out.println("ATTENTION "+f.id()+" "+f.code()+" : "+f.explanation()));
        System.out.println("Ce contrôle ne prouve ni l'indépendance des auteurs ni la justesse des corrigés.");
        if(!r.errors().isEmpty())System.exit(2);
    }
}
