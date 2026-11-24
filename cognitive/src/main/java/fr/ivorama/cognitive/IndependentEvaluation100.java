package fr.ivorama.cognitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/**
 * IVOC-EVAL-002: évaluateur en lecture seule de scénarios rédigés en dehors du développement.
 * Ce programme n'engendre jamais les 100 questions de certification.
 * Format TSV 11 colonnes documenté dans docs/evaluation/IVOC-EVAL-002.txt.
 * La politique de preuve est contrôlée indépendamment des textes français générés.
 */
public final class IndependentEvaluation100 {
    private IndependentEvaluation100() {}
    public enum Family { DEDUCTION, INCERTITUDE, DIALOGUE, FRANCAIS, TRANSFERT }
    public enum Outcome { YES, NO, UNKNOWN, NEED_INFORMATION, INCONSISTENT, RESOURCE_LIMIT,
        CLARIFICATION, UNINTERPRETED, WHY_PROVEN, WHY_UNPROVEN, INVESTIGATION_PLAN,
        INVESTIGATION_UNAVAILABLE, TEMPORAL_YES, TEMPORAL_NO, TEMPORAL_UNKNOWN,
        CAUSAL_UNPROVEN, WITHDRAWN }
    public enum Evidence { ANY, CHECKED_PROOF, NO_FALSE_CLOSURE, CONDITIONAL_ONLY }
    /** semantics counts ONLY scenarios with an explicitly supplied oracle formula.
     * semanticNotAssessed makes structurally non-propositional cases visible instead
     * of pretending their meaning has been checked.
     */
    public record Result(int total,int complete,int completeWithSemanticOracle,int acts,int semantics,int semanticEvaluated,
        int semanticNotAssessed,int initial,int finalAnswers,
        int evidence,int unjustifiedClosures,String sha256,Map<Family,Integer> familyPassed,List<String> failures) {
        public Result { familyPassed=Map.copyOf(familyPassed);failures=List.copyOf(failures); }
    }
    private record Case(String id,Family family,String entities,String setup,String question,
                        IntegratedQuestionCycle.ActState act,String meaning,Outcome initial,
                        String actions,Outcome finalOutcome,Evidence evidence) {}
    /** Accepte exactement 100 questions : interdit de transformer un test de développement en benchmark certifié. */
    public static Result evaluate(Path file) throws IOException {return run(file,true,null);}
    /** Vérifie l'empreinte décidée AVANT le test. Ne garantit pas l'indépendance des auteurs. */
    public static Result evaluateSealed(Path file,String expectedSha256) throws IOException {
        if(expectedSha256==null || !expectedSha256.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Empreinte SHA-256 préenregistrée obligatoire (64 caractères hexadécimaux)");
        return run(file,true,expectedSha256.toLowerCase(Locale.ROOT));
    }
    /** Uniquement pour valider l'outil et développer le logiciel : score NON INDÉPENDANT. */
    public static Result evaluateDevelopment(Path file) throws IOException {return run(file,false,null);}
    private static Result run(Path file,boolean blind,String expectedSha256) throws IOException {
        byte[] bytes=Files.readAllBytes(file);
        String actualHash=sha(bytes);
        if(expectedSha256!=null && !actualHash.equals(expectedSha256))
            throw new IllegalArgumentException("Corpus modifié ou mauvaise empreinte avant l'évaluation");
        List<Case> cases=new ArrayList<>();Set<String> ids=new HashSet<>();Set<String> canonical=new HashSet<>();
        Map<Family,Integer> sizes=new EnumMap<>(Family.class);
        for(String line:new String(bytes,StandardCharsets.UTF_8).lines().toList()) {
            if(line.isBlank() || line.startsWith("#"))continue;
            String[] f=line.split("\t",-1);
            if(f.length!=11)throw new IllegalArgumentException("EVAL-002 : 11 colonnes TSV requises");
            if(!f[0].matches("[A-Za-z0-9_-]+")||!ids.add(f[0]))throw new IllegalArgumentException("Id invalide ou dupliqué : "+f[0]);
            Family family=Family.valueOf(f[1]);
            var act=IntegratedQuestionCycle.ActState.valueOf(f[5]);
            Outcome initial=Outcome.valueOf(f[7]);Outcome finalStatus=Outcome.valueOf(f[9]);
            Evidence evidence=Evidence.valueOf(f[10]);
            if(f[6].equals("-")) {
                if(act==IntegratedQuestionCycle.ActState.INTERPRETED ||
                   act==IntegratedQuestionCycle.ActState.PARTITION)
                    throw new IllegalArgumentException("Oracle semantique obligatoire pour "+act+" : "+f[0]);
            } else OracleFormula.parse(f[6]);
            String unique=String.join("\t",Arrays.copyOfRange(f,2,f.length));
            if(!canonical.add(unique))throw new IllegalArgumentException("Scénario dupliqué à l'identique : "+f[0]);
            if(f[4].isBlank())throw new IllegalArgumentException("Question vide : "+f[0]);
            sizes.merge(family,1,Integer::sum);
            cases.add(new Case(f[0],family,f[2],f[3],f[4],act,f[6],initial,f[8],finalStatus,evidence));
        }
        if(blind && (cases.size()!=100 || Arrays.stream(Family.values()).anyMatch(k->sizes.getOrDefault(k,0)!=20)))
            throw new IllegalArgumentException("Corpus scellé incomplet : exactement 100 cas, 20 par famille, nécessaires");
        int full=0,completeWithSemanticOracle=0,acts=0,semantics=0,semanticEvaluated=0,semanticNotAssessed=0,initial=0,finalCount=0,evidence=0,unjustified=0;
        Map<Family,Integer> familyPassed=new EnumMap<>(Family.class);
        List<String> failures=new ArrayList<>();
        for(Case c:cases) {
            try {
                FrenchQuestionDialogue d=new FrenchQuestionDialogue();
                for(String s:parts(c.entities)) {
                    String[] p=s.strip().split("\\s+");
                    if(p.length!=2)throw new IllegalArgumentException("Entité 'catégorie identifiant' attendue : "+s);
                    d.declare(p[0],p[1]);
                }
                for(String s:parts(c.setup)) apply(d,s,null);
                FrenchQuestionDialogue.Reply original=d.ask(c.question);
                String id=original.questionId();
                if(d.cycle().questionActCount()!=1)throw new IllegalStateException("Un acte interrogatif attendu");
                var act=d.cycle().questionAct(id);
                boolean okAct=act.state()==c.act;
                if(okAct) acts++;
                Outcome firstOutcome=assess(d,id);
                boolean okFirst=firstOutcome==c.initial;
                if(okFirst)initial++;
                boolean semApplicable=!c.meaning.equals("-");
                if(semApplicable)semanticEvaluated++; else semanticNotAssessed++;
                boolean okSem=semApplicable && act.formula().isPresent() &&
                    OracleFormula.equivalent(act.formula().orElseThrow(),OracleFormula.parse(c.meaning));
                for(String s:parts(c.actions))apply(d,s,id);
                var endAct=d.cycle().questionAct(id);
                if(!okSem && c.act==IntegratedQuestionCycle.ActState.NEEDS_CLARIFICATION &&
                   !c.meaning.equals("-") && endAct.formula().isPresent())
                    okSem=OracleFormula.equivalent(endAct.formula().orElseThrow(),OracleFormula.parse(c.meaning));
                if(okSem)semantics++;
                Outcome obtained=assess(d,id);
                boolean okFinal=obtained==c.finalOutcome;
                if(okFinal)finalCount++;
                boolean okEvidence=checkEvidence(d,id,c.evidence);
                if(okEvidence)evidence++;
                boolean falseClosure=(isConclusive(firstOutcome) && !isConclusive(c.initial)) ||
                    (firstOutcome==Outcome.YES && c.initial==Outcome.NO) ||
                    (firstOutcome==Outcome.NO && c.initial==Outcome.YES) ||
                    (firstOutcome==Outcome.TEMPORAL_YES && c.initial==Outcome.TEMPORAL_NO) ||
                    (firstOutcome==Outcome.TEMPORAL_NO && c.initial==Outcome.TEMPORAL_YES) ||
                    (isConclusive(obtained) && !isConclusive(c.finalOutcome)) ||
                    (obtained==Outcome.YES && c.finalOutcome==Outcome.NO) ||
                    (obtained==Outcome.NO && c.finalOutcome==Outcome.YES) ||
                    (obtained==Outcome.WHY_PROVEN && c.finalOutcome!=Outcome.WHY_PROVEN);
                if((obtained==Outcome.TEMPORAL_YES && c.finalOutcome==Outcome.TEMPORAL_NO) ||
                   (obtained==Outcome.TEMPORAL_NO && c.finalOutcome==Outcome.TEMPORAL_YES))falseClosure=true;
                if(falseClosure || (isConclusive(obtained) && !okEvidence))unjustified++;
                if(okAct&&okFirst&&(!semApplicable || okSem)&&okFinal&&okEvidence) {full++;if(semApplicable)completeWithSemanticOracle++;familyPassed.merge(c.family,1,Integer::sum);}
                else failures.add(c.id+" : acte="+okAct+", sens="+(semApplicable?Boolean.toString(okSem):"NON_EVALUE")+", initial="+okFirst+
                    ", final attendu="+c.finalOutcome+" obtenu="+obtained+", preuve="+okEvidence);
            } catch(Exception ex) {
                // Une exception n'est JAMAIS un REJECT correct ; elle compte comme échec.
                failures.add(c.id+" : EXCEPTION "+ex.getClass().getSimpleName()+" : "+ex.getMessage());
            }
        }
        return new Result(cases.size(),full,completeWithSemanticOracle,acts,semantics,semanticEvaluated,semanticNotAssessed,initial,finalCount,evidence,unjustified,
            actualHash,familyPassed,failures);
    }
    private static boolean isConclusive(Outcome r) {
        return r==Outcome.YES||r==Outcome.NO||r==Outcome.WHY_PROVEN||r==Outcome.TEMPORAL_YES||r==Outcome.TEMPORAL_NO;
    }
    private static boolean checkEvidence(FrenchQuestionDialogue d,String id,Evidence policy) {
        if(policy==Evidence.ANY)return true;
        var act=d.cycle().questionAct(id);
        if(policy==Evidence.NO_FALSE_CLOSURE) {
            // Une obligation non résolue ne doit pas posséder de fausse formule.
            return switch(act.state()) {
                case NEEDS_CLARIFICATION,UNINTERPRETED,WITHDRAWN -> act.formula().isEmpty();
                default -> true;
            };
        }
        if(act.state()==IntegratedQuestionCycle.ActState.EXPLANATION) {
            var r=d.cycle().explanationQuestion(id);
            if(policy==Evidence.CHECKED_PROOF)return r.certificate().isPresent() &&
                r.certificate().orElseThrow().proofChecked() &&
                r.certificate().orElseThrow().basis()!=ProvenanceAudit.Basis.DIRECT_UNVERIFIED;
            return r.status()!=IntegratedQuestionCycle.ExplanationStatus.PROVEN_FORMALLY;
        }
        if(act.state()!=IntegratedQuestionCycle.ActState.INTERPRETED)return false;
        var r=d.status(id).reportOptional();
        if(r.isEmpty())return false;
        if(policy==Evidence.CHECKED_PROOF)return r.orElseThrow().proofChecked() &&
            r.orElseThrow().certificate().filter(c->c.proofChecked() &&
                c.basis()!=ProvenanceAudit.Basis.DIRECT_UNVERIFIED).isPresent();
        return r.orElseThrow().status()!=IntegratedQuestionCycle.Status.RESTORED_FORMALLY &&
            r.orElseThrow().support()!=IntegratedQuestionCycle.Support.INDEPENDENTLY_VERIFIED;
    }
    private static Outcome assess(FrenchQuestionDialogue d,String id) {
        var a=d.cycle().questionAct(id);
        return switch(a.state()) {
            case NEEDS_CLARIFICATION -> Outcome.CLARIFICATION;
            case UNINTERPRETED -> Outcome.UNINTERPRETED;
            case WITHDRAWN -> Outcome.WITHDRAWN;
            case EXPLANATION -> switch(d.cycle().explanationQuestion(id).status()) {
                case PROVEN_CONDITIONALLY,PROVEN_FORMALLY -> Outcome.WHY_PROVEN;
                default -> Outcome.WHY_UNPROVEN;
            };
            case INVESTIGATION -> switch(d.cycle().investigationQuestion(id).status()) {
                case PLAN_AVAILABLE,ALREADY_DETERMINED -> Outcome.INVESTIGATION_PLAN;
                default -> Outcome.INVESTIGATION_UNAVAILABLE;
            };
            case TEMPORAL -> {
                var q=d.cycle().temporalQuestion(id);
                var r=d.temporalLedger().assessAtVersion(q.frame(),q.asOfJournalVersion().orElse(d.temporalLedger().version()));
                yield switch(r.finding()) {
                    case ACTIVE_CONDITIONALLY,ARCHIVED_CONDITIONALLY -> Outcome.TEMPORAL_YES;
                    case INACTIVE_CONDITIONALLY -> Outcome.TEMPORAL_NO;
                    case CAUSAL_CLAIM_ONLY -> Outcome.CAUSAL_UNPROVEN;
                    case NOT_ESTABLISHED -> Outcome.TEMPORAL_UNKNOWN;
                };
            }
            case INTERPRETED -> {
                var r=d.status(id).report();
                yield switch(r.status()) {
                    case RESTORED_CONDITIONALLY,RESTORED_FORMALLY,RESTORED_JUSTIFIED ->
                        r.answer().orElseThrow()?Outcome.YES:Outcome.NO;
                    case OPEN -> Outcome.UNKNOWN;
                    case NEED_INFORMATION -> Outcome.NEED_INFORMATION;
                    case INCONSISTENT -> Outcome.INCONSISTENT;
                    case RESOURCE_LIMIT -> Outcome.RESOURCE_LIMIT;
                };
            }
            case ONTOLOGICAL, ORIENTING -> throw new IllegalStateException("Question ontologique non exposée par le dialogue français");
            case PARTITION -> throw new IllegalStateException("Partition non exposée par le dialogue français");
        };
    }
    private static void apply(FrenchQuestionDialogue d,String operation,String id) {
        String raw=operation.strip();String lower=raw.toLowerCase(Locale.ROOT);
        if(lower.startsWith("relation ")) {
            String[] halves=raw.substring(9).strip().split("\\s*\\|\\s*",-1);
            if((halves.length!=2 && halves.length!=3) || halves[1].isBlank())
                throw new IllegalArgumentException("relation concept sujetKind objetKind verbe | est ... par/dans");
            String[] f=halves[0].split("\\s+");
            if(f.length!=4)throw new IllegalArgumentException("Définition relationnelle invalide");
            if(halves.length==3) d.registerRelation(f[0],f[1],f[2],f[3],halves[1],halves[2]);
            else d.registerRelation(f[0],f[1],f[2],f[3],halves[1]);
        } else if(lower.startsWith("lexeme ")||lower.startsWith("lexeme-ambigu ")) {
            boolean multiple=lower.startsWith("lexeme-ambigu ");
            String[] f=raw.substring(multiple?14:7).strip().split("\\s+");
            if(f.length<5||f.length>6)throw new IllegalArgumentException("Définition lexicale invalide");
            Set<String> kinds=f.length==6?Set.copyOf(Arrays.asList(f[5].split(",")))
                :Set.of("document","dossier","porte","objet");
            if(multiple)d.registerAmbiguousAdjective(f[0],kinds,f[1],f[2],f[3],f[4]);
            else d.registerAdjective(f[0],kinds,f[1],f[2],f[3],f[4]);
        } else if(lower.startsWith("entite ")) {
            String[] p=raw.substring(7).split("\\s+");
            if(p.length!=2)throw new IllegalArgumentException("entite <catégorie> <id>");
            d.declare(p[0],p[1]);
        } else if(lower.startsWith("sais "))d.teach(raw.substring(5));
        else if(lower.startsWith("evenement "))d.declareTemporalEvent(raw);
        else if(lower.startsWith("cause "))d.declareCausalClaim(raw);
        else if(lower.startsWith("retire-evenement "))d.retractTemporalEvent(raw.substring(17).strip(),"Benchmark externe");
        else if(lower.startsWith("retire-cause "))d.retractCausalClaim(raw.substring(13).strip(),"Benchmark externe");
        else if(lower.startsWith("retire "))d.retract(raw.substring(7));
        else if(lower.startsWith("precise ")) {
            if(id==null)throw new IllegalArgumentException("Clarification sans acte Q");
            int actsBefore=d.cycle().questionActCount();
            int factsBefore=d.cycle().premises().size();
            var attempt=d.tryClarify(id,raw.substring(8));
            if(!attempt.accepted() && (!d.pendingIds().contains(id) ||
                    d.cycle().questionActCount()!=actsBefore ||
                    d.cycle().premises().size()!=factsBefore))
                throw new IllegalStateException("Choix rejeté a modifié l'acte ou les faits");
        } else if(lower.equals("annule")) {
            if(id==null)throw new IllegalArgumentException("Retrait sans Q");d.withdraw(id);
        } else if(lower.equals("observe oui")||lower.equals("observe non")) {
            if(id==null)throw new IllegalArgumentException("Observation sans Q");
            d.answerObservation(id,lower.equals("observe oui"),"Réponse du scénario");
        } else throw new IllegalArgumentException("Opération inconnue : "+raw);
    }
    private static List<String> parts(String value){return value.isBlank()?List.of():Arrays.stream(value.split("\\s*;\\s*"))
        .filter(x->!x.isBlank()).toList();}
    private static String sha(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static void main(String[] args) throws IOException {
        if(args.length!=2)throw new IllegalArgumentException(
            "Usage : IndependentEvaluation100 <corpus-100.tsv> <sha256-scelle-prealablement>");
        Result r=evaluateSealed(Path.of(args[0]),args[1]);
        System.out.println("IVOC-EVAL-002 : "+r.complete()+"/"+r.total()+" réussites sur dimensions applicables (dont "+r.completeWithSemanticOracle()+" avec oracle sémantique explicite) ; "+
            "compréhension vérifiée="+r.semantics()+"/"+r.semanticEvaluated()+" (non évaluée="+r.semanticNotAssessed()+"), type Q="+r.acts()+", réponses finales="+r.finalAnswers()+
            ", statut preuve="+r.evidence()+", clôtures injustifiées="+r.unjustifiedClosures());
        System.out.println("Empreinte corpus="+r.sha256()+" ; résultats par famille="+r.familyPassed());
        r.failures().forEach(s->System.out.println("ECHEC "+s));
    }
}
