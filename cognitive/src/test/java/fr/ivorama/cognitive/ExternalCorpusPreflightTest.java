package fr.ivorama.cognitive;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ExternalCorpusPreflightTest {
    private static int assertions=0;
    private static void ok(boolean pass,String msg){assertions++;if(!pass)throw new AssertionError(msg);}
    public static void main(String[] args) throws Exception {
        var dev=ExternalCorpusPreflight.analyze(Path.of("evaluation/dev/SCENARIOS-DEVELOPPEMENT.tsv"));
        ok(dev.cases()==10,"jeu développement inchangé");
        ok(dev.errors().stream().anyMatch(e->e.code().equals("COUNT")),"10 n'est pas un examen de 100");
        ok(dev.errors().stream().anyMatch(e->e.code().equals("BALANCE")),"contrôle des familles");
        ok(dev.errors().stream().noneMatch(e->e.code().equals("ORACLE")),"oracle du jeu développement lisible");
        var lines=new ArrayList<>(Files.readAllLines(Path.of("evaluation/dev/SCENARIOS-DEVELOPPEMENT.tsv")));
        Path tmp=Files.createTempFile("ivoc-preflight", ".tsv");
        try {
            var mutated=new ArrayList<>(lines);
            mutated.set(1,mutated.get(1).replace("approuve_document_a","and("));
            Files.write(tmp,mutated,StandardCharsets.UTF_8);
            var r=ExternalCorpusPreflight.analyze(tmp);
            ok(r.errors().stream().anyMatch(e->e.code().equals("ORACLE")),"oracle invalide rejeté");
            mutated=new ArrayList<>(lines);
            mutated.add(lines.get(1).replace("DEV01","DEV11"));
            Files.write(tmp,mutated,StandardCharsets.UTF_8);
            r=ExternalCorpusPreflight.analyze(tmp);
            ok(r.errors().stream().anyMatch(e->e.code().equals("DUPLICATE")),"copie exacte interdite");
            ok(r.warnings().stream().anyMatch(e->e.code().equals("NEAR_DUPLICATE")),"quasi doublon signalé");
            mutated=new ArrayList<>(lines);
            mutated.set(1,mutated.get(1).replace("approuve_document_a","-"));
            Files.write(tmp,mutated,StandardCharsets.UTF_8);
            r=ExternalCorpusPreflight.analyze(tmp);
            ok(r.errors().stream().anyMatch(e->e.code().equals("MISSING_SEMANTICS")),"sens manquant rejete");
            ok(r.sha256().matches("[0-9a-f]{64}"),"hash du fichier calculé");
            // Contrat IVOC-EVAL-004 : absence d'oracle propositionnel explicite
            // = sémantique NON ÉVALUÉE, jamais « comprise correctement ».
            String nonProp=String.join("\t","SEM_NO_ORACLE","FRANCAIS","document a",
                "sais Le document a est archivé.","Pourquoi le document a est-il archivé ?",
                "EXPLANATION","-","WHY_UNPROVEN","","WHY_UNPROVEN","ANY");
            Files.writeString(tmp,nonProp+"\n",StandardCharsets.UTF_8);
            var preNonProp=ExternalCorpusPreflight.analyze(tmp);
            ok(preNonProp.errors().stream().noneMatch(e->e.code().equals("MISSING_SEMANTICS")),
                "explication sans formule autorisée par le protocole");
            var scoredNonProp=IndependentEvaluation100.evaluateDevelopment(tmp);
            ok(scoredNonProp.semanticNotAssessed()==1 && scoredNonProp.semanticEvaluated()==0 &&
                scoredNonProp.semantics()==0 && scoredNonProp.complete()==1 &&
                scoredNonProp.completeWithSemanticOracle()==0,
                "question non propositionnelle : dimensions applicables réussies, sémantique non évaluée : "+scoredNonProp.failures());
            String investigation=String.join("\t","SEM_INV","INCERTITUDE","document a",
                "","Comment savoir si le document a est archivé ?", "INVESTIGATION","-",
                "INVESTIGATION_PLAN","","INVESTIGATION_PLAN","ANY");
            Files.writeString(tmp,investigation+"\n",StandardCharsets.UTF_8);
            var pInv=ExternalCorpusPreflight.analyze(tmp);
            ok(pInv.errors().stream().noneMatch(e->e.code().equals("MISSING_SEMANTICS")),
                "investigation sans oracle propositionnel autorisée");
            var sInv=IndependentEvaluation100.evaluateDevelopment(tmp);
            ok(sInv.semanticNotAssessed()==1 && sInv.semantics()==0,
                "investigation ne gagne pas artificiellement de points sémantiques");
            var relation=String.join("\t","DEV_REL","TRANSFERT","document a;dossier b",
                "relation repere document dossier repère | est repéré par;sais Le document a repère le dossier b.",
                "Le dossier b est-il repéré par le document a ?","INTERPRETED",
                "relation_repere_document_a_dossier_b","YES","","YES","CONDITIONAL_ONLY");
            Files.writeString(tmp,relation+"\n",StandardCharsets.UTF_8);
            var evaluated=IndependentEvaluation100.evaluateDevelopment(tmp);
            ok(evaluated.complete()==1,"inscription dynamique de relation accessible au benchmark : "+evaluated.failures());
            String initialGoldWrong=relation.replace("\tYES\t\tYES\t","\tUNKNOWN\t\tYES\t");
            Files.writeString(tmp,initialGoldWrong+"\n",StandardCharsets.UTF_8);
            evaluated=IndependentEvaluation100.evaluateDevelopment(tmp);
            ok(evaluated.complete()==0 && evaluated.unjustifiedClosures()==1,
                "clôture initiale injustifiée comptabilisée même si résultat final correct");
            var fake=new ArrayList<String>();
            for(String fam:List.of("DEDUCTION","INCERTITUDE","DIALOGUE","FRANCAIS","TRANSFERT"))
                for(int i=0;i<20;i++) fake.add(String.join("\t","SIM"+fam+i,fam,"document a","",
                    "Texte artificiel "+fam+" "+i+" ?","UNINTERPRETED","-","UNINTERPRETED","",
                    "UNINTERPRETED","NO_FALSE_CLOSURE"));
            Files.write(tmp,fake,StandardCharsets.UTF_8);
            var pre=ExternalCorpusPreflight.analyze(tmp);
            ok(pre.cases()==100 && pre.byFamily().values().stream().allMatch(n->n==20)
                    && pre.errors().isEmpty(),"gabarit mécanique 100 cas accepté seulement syntaxiquement");
        } finally{Files.deleteIfExists(tmp);}
        System.out.println("EXTERNAL CORPUS PREFLIGHT TEST OK - "+assertions+" assertions; non indépendant");
    }
}
