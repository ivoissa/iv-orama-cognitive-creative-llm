package fr.ivorama.cognitive;

import java.nio.file.*;
import java.util.*;

/** Banc développement de l'évaluateur, NON indépendant et NON scellé. */
public final class EvaluationFrameworkTest {
    private static int assertions;
    private static void check(boolean condition, String msg) {assertions++;if(!condition)throw new AssertionError(msg);}
    private static void fail(Runnable r,String msg) {boolean failed=false;
        try{r.run();}catch(IllegalArgumentException e){failed=true;}
        check(failed,msg);
    }
    public static int runAll() throws Exception {
        assertions=0;
        var f=OracleFormula.parse("not(and(atom(p),atom(q)))");
        check(f.equals(Formula.not(Formula.and(Formula.atom("p"),Formula.atom("q")))),"analyseur oracle");
        check(OracleFormula.equivalent(OracleFormula.parse("implies(p,q)"),
                OracleFormula.parse("not(and(p,not(q)))")),"équivalence non structurelle");
        check(!OracleFormula.equivalent(Formula.atom("p"),Formula.atom("q")),"distinction de sens");
        fail(()->OracleFormula.parse("and(p,)") ,"oracle mal formé rejeté");
        fail(()->OracleFormula.parse("p trailing"),"suffixe interdit");
        Path dev=Path.of("evaluation/dev/SCENARIOS-DEVELOPPEMENT.tsv");
        var score=IndependentEvaluation100.evaluateDevelopment(dev);
        fail(()->{try{IndependentEvaluation100.evaluateSealed(dev,"0".repeat(64));}
            catch(java.io.IOException e){throw new IllegalStateException(e);}},"empreinte incorrecte rejetée avant l'évaluation");
        check(score.total()==10,"jeu de développement : dix cas");
        check(score.completeWithSemanticOracle()==6,"six scénarios notés avec oracle sémantique");
        check(score.complete()==10,"toutes les dimensions du banc interne doivent être correctes : "+score.failures());
        check(score.acts()==10 && score.semantics()==6 && score.semanticEvaluated()==6 &&
            score.semanticNotAssessed()==4,"nature et sens interrogatif : 4 cas non propositionnels non notés en sémantique");
        check(score.initial()==10 && score.finalAnswers()==10,"avant et après révision");
        check(score.evidence()==10 && score.unjustifiedClosures()==0,"qualité et faux positifs");
        check(score.familyPassed().values().stream().mapToInt(Integer::intValue).sum()==10,"scores par famille");
        try {
            IndependentEvaluation100.evaluate(dev);
            throw new AssertionError("Un corpus de 10 cas ne doit jamais être annoncé comme EVAL-001 !");
        }catch(IllegalArgumentException expected){assertions++;}
        Path tmp=Files.createTempFile("ivoc-eval-revue", ".tsv");
        try{
            var lines=Files.readAllLines(dev);
            var mutated=new ArrayList<>(lines);
            // Test d'oracle volontairement erroné, doit échouer même lorsque le texte ressemble au bon résultat.
            String original=mutated.get(1);
            mutated.set(1, original.replace("\tYES\t\tYES\t", "\tNO\t\tNO\t"));
            Files.write(tmp,mutated);
            var r=IndependentEvaluation100.evaluateDevelopment(tmp);
            check(r.complete()<10,"oracle incorrect détecté");
            check(r.unjustifiedClosures()>0,"clôture injustifiée détectée");
            // Un scénario INTERPRETED dépourvu de formule ne peut pas
            // être déclaré correct par omission de sa dimension sémantique.
            mutated.set(1,original.replace("approuve_document_a","-"));
            Files.write(tmp,mutated);
            try {IndependentEvaluation100.evaluateDevelopment(tmp);
                throw new AssertionError("INTERPRETED sans oracle ne doit pas passer");
            }catch(IllegalArgumentException expected){assertions++;}
            mutated.set(1,original);
            // L'échec interne doit toujours rester visible dans les résultats.
            mutated.set(1,original.replace("Le document a est-il validé ?","Texte indéchiffrable ?"));
            Files.write(tmp,mutated);
            r=IndependentEvaluation100.evaluateDevelopment(tmp);
            check(r.complete()<10,"NLU hors grammaire échoue : aucune correction implicite");
            mutated.set(1,original.replace("CONDITIONAL_ONLY","CHECKED_PROOF"));
            Files.write(tmp,mutated);
            r=IndependentEvaluation100.evaluateDevelopment(tmp);
            check(r.complete()<10 && r.unjustifiedClosures()>0,
                "La déclaration directe n'est pas une preuve formelle contrôlée");
        }finally {Files.deleteIfExists(tmp);}
        return assertions;
    }
    public static void main(String[] args)throws Exception {System.out.println("EVAL-002 FRAMEWORK TEST OK - "+runAll()+" assertions ; corpus interne non indépendant");}
}
