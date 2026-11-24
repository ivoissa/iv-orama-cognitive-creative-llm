package fr.ivorama.cognitive;

import java.util.*;

/** P0.2 deterministic end-to-end semantic and syntactic provenance checks. */
public final class P02SemanticLineageTestMain {
    private static int tests;
    private static void check(boolean good, String reason) {
        tests++;
        if (!good) throw new AssertionError("P0.2 check " + tests + ": " + reason);
    }
    private static boolean containsSupport(ProvenanceAudit.Certificate c, Set<Formula> formulas) {
        return c.semanticSupportLineage().orElseThrow().stream()
                .anyMatch(group -> group.formulas().equals(formulas));
    }
    private static ProvenanceAudit.SemanticSupport lookup(ProvenanceAudit.Certificate c, Set<Formula> formulas) {
        return c.semanticSupportLineage().orElseThrow().stream()
                .filter(group -> group.formulas().equals(formulas)).findFirst().orElseThrow();
    }
    private static Set<String> sourceIds(ProvenanceAudit.SemanticSupport s, Formula formula) {
        return s.requiredPremises().stream().filter(d -> d.formula().equals(formula))
                .flatMap(d -> d.alternativeActiveSources().stream())
                .map(ProvenanceAudit.Source::id).collect(java.util.stream.Collectors.toSet());
    }
    public static int runAll() {
        tests = 0;
        Formula a = Formula.atom("a"), b = Formula.atom("b"), q = Formula.atom("q");
        Formula aq = Formula.implies(a,q), bq = Formula.implies(b,q), c = Formula.atom("c");
        IntegratedQuestionCycle cycle = new IntegratedQuestionCycle();
        String a1 = cycle.teach(a, "A source 1");
        String a2 = cycle.teach(a, "A source 2");
        cycle.teach(aq, "A implies Q");
        String b1 = cycle.teach(b, "B source");
        cycle.teach(bq, "B implies Q");
        cycle.teach(c, "unrelated source");
        var first = cycle.ask(q);
        var cert = first.certificate().orElseThrow();
        check(first.proofChecked(),"syntactic proof still available");
        check(cert.semanticSupportLineage().isPresent(),"semantic lineage snapshot present");
        check(cert.alternativeSupports().status()==SupportAnalyzer.Status.COMPLETE,"support enumeration complete");
        check(cert.semanticSupportLineage().orElseThrow().size()==2,"two independent minimal semantic supports");
        check(containsSupport(cert,Set.of(a,aq)),"A branch present");
        check(containsSupport(cert,Set.of(b,bq)),"B branch present");
        check(sourceIds(lookup(cert,Set.of(a,aq)),a).equals(Set.of(a1,a2)),"same formula alternative declared sources");
        check(cert.dependencies().stream().allMatch(d -> d.alternativeActiveSources().size()>0),"proof deps valid");
        check(!cert.semanticSupportSummary().contains("0 groupe"),"no false zero support");
        check(cert.semanticSupportSummary().contains("énumération complète"),"exhaustive correctly labelled");
        check(!first.explanation().contains("Appuis : 0"),"cycle uses separate semantic support summary");
        cycle.retract(a1);
        var afterOne = cycle.question(first.id());
        check(!afterOne.reopened(),"other same-formula source preserves result");
        var next = afterOne.certificate().orElseThrow();
        check(sourceIds(lookup(next,Set.of(a,aq)),a).equals(Set.of(a2)),"removed source excluded from new snapshot");
        check(sourceIds(lookup(cert,Set.of(a,aq)),a).equals(Set.of(a1,a2)),"historical snapshot stable");
        check(!cycle.isCertificateCurrent(first.id(),cert),"old certificate is stale after revision");
        cycle.retract(a2);
        var afterBoth = cycle.question(first.id());
        check(afterBoth.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,"independent B branch preserves answer");
        check(afterBoth.certificate().orElseThrow().semanticSupportLineage().orElseThrow().size()==1,"only B support remains");
        check(containsSupport(afterBoth.certificate().orElseThrow(),Set.of(b,bq)),"B branch traced");
        cycle.retract(b1);
        var reopened = cycle.question(first.id());
        check(reopened.reopened(),"question reopened when all active evidence removed");
        check(reopened.certificate().isEmpty(),"no stale certified evidence");

        IntegratedQuestionCycle model = new IntegratedQuestionCycle();
        Formula notNotA = Formula.not(Formula.not(a));
        String nn = model.teach(notNotA,"double-negative premise");
        var semantic = model.ask(a);
        var semanticCert = semantic.certificate().orElseThrow();
        check(semantic.support()==IntegratedQuestionCycle.Support.MODEL_ONLY_CONDITIONAL,"classical entailment without search proof");
        check(!semanticCert.proofChecked() && semanticCert.dependencies().isEmpty(),"no fabricated syntax dependencies");
        check(semanticCert.semanticSupportLineage().orElseThrow().size()==1,"model-only support is captured");
        check(containsSupport(semanticCert,Set.of(notNotA)),"model-only dependence on not-not-A");
        check(sourceIds(lookup(semanticCert,Set.of(notNotA)),notNotA).equals(Set.of(nn)),"model-only original source retained");
        check(semantic.explanation().contains("Supports sémantiques : 1"),"honest model-only explanation");

        var theoremCycle = new IntegratedQuestionCycle();
        var theorem = theoremCycle.ask(Formula.implies(a,a)).certificate().orElseThrow();
        check(theorem.hasPremiseFreeFormalCertificate(),"theorem proved without assumptions");
        check(theorem.semanticSupportLineage().orElseThrow().size()==1,"tautology one empty support");
        check(theorem.semanticSupportLineage().orElseThrow().get(0).formulas().isEmpty(),"tautology has no semantic premise");
        check(theorem.semanticSupportSummary().contains("sans prémisse externe"),"tautology explanation honest");

        var unknown = new IntegratedQuestionCycle();
        check(unknown.ask(a).certificate().isEmpty(),"unknown cannot mint support certificate");
        var contradicted = new IntegratedQuestionCycle();
        contradicted.teach(a,"positive");
        contradicted.teach(Formula.not(a),"negative");
        check(contradicted.ask(q).certificate().isEmpty(),"inconsistency cannot mint support certificate");

        var incomplete = new SupportAnalyzer.Result(SupportAnalyzer.Status.SEARCH_LIMIT,
                List.of(Set.of(a)), 3, "bounded");
        var source = new ProvenanceAudit.Source("PX","declared",KnowledgeBase.Kind.ASSERTION);
        var dependency = new ProvenanceAudit.Dependency(a,List.of(source));
        var sample = new ProvenanceAudit.Certificate(q,ProvenanceAudit.Basis.MODEL_ONLY_CONDITIONAL,
                false,Optional.empty(),List.of(),5,"test",incomplete,
                Optional.of(List.of(new ProvenanceAudit.SemanticSupport(List.of(dependency)))));
        check(sample.semanticSupportSummary().contains("résultat partiel"),"partial support not labelled exhaustive");
        boolean rejected = false;
        try {
            new ProvenanceAudit.Certificate(q,ProvenanceAudit.Basis.MODEL_ONLY_CONDITIONAL,
                false,Optional.empty(),List.of(),5,"test",incomplete,
                Optional.of(List.of(new ProvenanceAudit.SemanticSupport(List.of(
                        new ProvenanceAudit.Dependency(b,List.of(source)))))));
        } catch(IllegalArgumentException ex) {rejected=true;}
        check(rejected,"forged semantic formula set cannot be attached to certificate");
        var legacy = new ProvenanceAudit.Certificate(q,ProvenanceAudit.Basis.MODEL_ONLY_CONDITIONAL,
                false,Optional.empty(),List.of(),5,"legacy",incomplete);
        check(legacy.semanticSupportLineage().isEmpty(),"legacy constructor does not fake captured evidence");
        check(legacy.semanticSupportSummary().contains("non capturée"),"legacy evidence is not called zero sources");
        return tests;
    }
    public static void main(String[] args) {
        System.out.println("P0.2 SEMANTIC LINEAGE PASS - "+runAll()+" checks");
    }
}
