package fr.ivorama.cognitive;

import java.util.*;

/** Banc de vérifications autonome, exécutable par javac/java sans Maven. */
public final class SelfTest {
    private static int assertions;
    private SelfTest() {}
    private static void check(boolean ok, String why) {
        assertions++;
        if (!ok) throw new AssertionError("FAILED: " + why);
    }
    private static <T extends Throwable> void expectThrows(Class<T> exception, Runnable action, String why) {
        boolean thrown = false;
        try { action.run(); } catch (Throwable ex) {
            if (!exception.isInstance(ex)) throw new AssertionError("Wrong exception: " + why, ex);
            thrown = true;
        }
        check(thrown, why);
    }

    public static void proofTests() {
        Formula p = Formula.atom("p"), q = Formula.atom("q");
        Proof mp = new Proof.ModusPonens(new Proof.Assumption(p),
                new Proof.Assumption(Formula.implies(p, q)));
        Set<Formula> context = Set.of(p, Formula.implies(p,q));
        ProofChecker.Result result = ProofChecker.verify(mp, context);
        check(result.valid() && q.equals(result.conclusion()), "modus ponens proof and target");
        check(result.dependencies().containsAll(context), "proof premise trace");
        check(!ProofChecker.verify(mp, Set.of(Formula.implies(p,q))).valid(), "unauthorized assumption rejected");
        check(!ProofChecker.verify(new Proof.ModusPonens(new Proof.Assumption(q),
                new Proof.Assumption(Formula.implies(p,q))), Set.of(q, Formula.implies(p,q))).valid(),
                "modus ponens incorrect antecedent rejected");
        Proof identity = new Proof.ImplicationIntroduction(p, new Proof.Assumption(p));
        ProofChecker.Result identityResult = ProofChecker.verify(identity, Set.of());
        check(identityResult.valid(), "implication introduction / discharge");
        check(Formula.implies(p,p).equals(identityResult.conclusion()), "p → p without premises");
        check(identityResult.dependencies().isEmpty(), "hypothesis discharged");
        Proof conjunction = new Proof.AndIntroduction(new Proof.Assumption(p), new Proof.Assumption(q));
        check(ProofChecker.verify(new Proof.AndRight(conjunction), Set.of(p,q)).conclusion().equals(q),
                "conjunction right elimination");
        check(!ProofChecker.verify(new Proof.BottomElimination(new Proof.Assumption(p), q), Set.of(p)).valid(),
                "bottom elimination without contradiction rejected");
        check(ProofChecker.verify(new Proof.BottomElimination(new Proof.Assumption(Formula.Bottom.INSTANCE), q),
                Set.of(Formula.Bottom.INSTANCE)).valid(), "bottom elimination is a formal derivation");
        check(ProofSearch.find(context, q, 10).isPresent(), "bounded forward derivation found");
        check(ProofSearch.find(Set.of(Formula.implies(p,q)), q, 10).isEmpty(),
                "implication alone does not prove consequent");
        expectThrows(IllegalArgumentException.class, () -> Formula.atom("invalid name"), "invalid atoms rejected");
        expectThrows(IllegalArgumentException.class, () -> ProofSearch.find(context, q, 0), "invalid budget rejected");
    }

    public static void semanticTests() {
        Formula p = Formula.atom("p"), q = Formula.atom("q");
        var budget = ModelExplorer.Budget.small();
        check(ModelExplorer.analyze(Set.of(p, Formula.implies(p,q)),q,budget).status()
                == ModelExplorer.Status.PROVEN_TRUE, "consequence all models");
        check(ModelExplorer.analyze(Set.of(Formula.implies(p,q)),q,budget).status()
                == ModelExplorer.Status.UNDETERMINED, "truth unknown without antecedent");
        check(ModelExplorer.analyze(Set.of(Formula.not(p)),p,budget).status()
                == ModelExplorer.Status.PROVEN_FALSE, "entailment of negation");
        check(ModelExplorer.analyze(Set.of(p, Formula.not(p)), q,budget).status()
                == ModelExplorer.Status.INCONSISTENT, "inconsistent context separated from proven truth");
        check(ModelExplorer.analyze(Set.of(),p,new ModelExplorer.Budget(0,1)).status()
                == ModelExplorer.Status.RESOURCE_LIMIT, "budget exhaustion not classified as falsity");
        Formula r = Formula.atom("r");
        ModelExplorer.Analysis unknown = ModelExplorer.analyze(Set.of(Formula.implies(p,q)), q,budget);
        check(unknown.trueWitness().isPresent() && unknown.falseWitness().isPresent(),
                "both witnesses for indetermination");
        check(unknown.trueWitness().get().satisfies(q), "positive witness actually satisfies question");
        check(!unknown.falseWitness().get().satisfies(q), "negative witness actually refutes question");
        check(ModelExplorer.analyze(Set.of(),r,budget).possibleWorlds().size() == 2,
                "open-world semantics for unobserved atom");
        check(new Quotation(0,p).targetLevel() == 1, "reflexive citation increases level");
        check(!new Quotation(0,p).equals(new Quotation(0,q)), "different statements not conflated");
        expectThrows(IllegalArgumentException.class, () -> new Quotation(-1, p), "negative reflexive level rejected");
    }

    public static void memoryTests() {
        Formula p = Formula.atom("p"), q = Formula.atom("q");
        KnowledgeBase kb = new KnowledgeBase();
        kb.addPremise("a", p, "human assertion", KnowledgeBase.Kind.ASSERTION);
        kb.addPremise("b", Formula.implies(p,q), "human assertion", KnowledgeBase.Kind.ASSERTION);
        Proof proof = ProofSearch.find(kb.activePremises(),q,100).orElseThrow();
        kb.recordProof("cert",proof);
        check(kb.supportedConclusion("cert").orElseThrow().equals(q), "conclusion supported");
        check(kb.version() == 3 && kb.journal().size() == 3, "audit journal records additions");
        kb.retractPremise("a");
        check(!kb.isProofActive("cert"), "retraction suspends dependent proof");
        check(kb.supportedConclusion("cert").isEmpty(), "no unsupported conclusion published");
        check(kb.journal().size() == 4 && kb.premisesHistory().size() == 2,
                "retracted knowledge retained in history");
        kb.addPremise("a2", p, "second independent assertion", KnowledgeBase.Kind.ASSERTION);
        check(kb.isProofActive("cert"), "new accepted source restores proof validity");
        expectThrows(IllegalArgumentException.class, () -> kb.addPremise("a2",p,"x",KnowledgeBase.Kind.ASSERTION),
                "duplicate ids forbidden");
        KnowledgeBase contradictory = new KnowledgeBase();
        contradictory.addPremise("x",p,"A",KnowledgeBase.Kind.ASSERTION);
        contradictory.addPremise("y",Formula.not(p),"B",KnowledgeBase.Kind.ASSERTION);
        expectThrows(IllegalStateException.class,
                () -> contradictory.recordProof("bad",new Proof.Assumption(p)),
                "contradictory context prevents certificate publication");
        KnowledgeBase tooLarge = new KnowledgeBase();
        for (int i=0;i<13;i++) {
            tooLarge.addPremise("k"+i,Formula.atom("x"+i),"simulated",KnowledgeBase.Kind.ASSERTION);
        }
        tooLarge.recordProof("independent",new Proof.Assumption(Formula.atom("x0")));
        check(tooLarge.isProofActive("independent"),
                "13 unrelated facts can be verified by separated components");
        KnowledgeBase coupled = new KnowledgeBase();
        Formula conjunction = Formula.atom("x0");
        for(int i=1;i<13;i++) conjunction=Formula.and(conjunction,Formula.atom("x"+i));
        coupled.addPremise("all",conjunction,"simulated",KnowledgeBase.Kind.ASSERTION);
        Formula complex = conjunction;
        expectThrows(IllegalStateException.class,
                () -> coupled.recordProof("unknown",new Proof.Assumption(complex)),
                "13 coupled atoms remain outside declared component budget");
    }

    public static void rdoTests() {
        Formula a=Formula.atom("Pa"), b=Formula.atom("Pb"), c=Formula.atom("Pc"), d=Formula.atom("Pd");
        Set<Formula> knowledge=Set.of(a,Formula.iff(b,c));
        var planner = RdoPlanner.propose(knowledge, c,
                List.of(new RdoPlanner.Candidate(a,1),new RdoPlanner.Candidate(d,1),
                        new RdoPlanner.Candidate(b,2)), ModelExplorer.Budget.small());
        check(planner.analysis().status()==ModelExplorer.Status.UNDETERMINED, "question initially open");
        check(planner.choice().isPresent(), "RDO candidate available");
        check(planner.choice().get().candidate().observation().equals(b),
                "RDO prefers test that determines the question over irrelevant split");
        check(planner.choice().get().guaranteesQuestion()==1, "both outcomes resolve target");
        check(ModelExplorer.analyze(knowledge,c,ModelExplorer.Budget.small()).status()
                == ModelExplorer.Status.UNDETERMINED, "planner does not fabricate observation");
        Set<Formula> observed=new HashSet<>(knowledge); observed.add(b);
        check(ModelExplorer.analyze(observed,c,ModelExplorer.Budget.small()).status()
                == ModelExplorer.Status.PROVEN_TRUE, "new simulated observation resolves target");
        Proof derived=ProofSearch.find(observed,c,100).orElseThrow();
        check(ProofChecker.verify(derived,observed).valid(), "RDO result independently provable");
        var noPlan=RdoPlanner.propose(Set.of(c),c,List.of(new RdoPlanner.Candidate(b,1)),
                ModelExplorer.Budget.small());
        check(noPlan.choice().isEmpty(), "no planning if query already established");
        var limited=RdoPlanner.propose(knowledge,c,List.of(new RdoPlanner.Candidate(b,1)),
                new ModelExplorer.Budget(1,2));
        check(limited.analysis().status()==ModelExplorer.Status.RESOURCE_LIMIT
                && limited.choice().isEmpty(), "RDO honors explicit limit");
    }

    public static void questionGapTests() {
        Formula p = Formula.atom("p"), q = Formula.atom("q"), r = Formula.atom("r");
        var budget = ModelExplorer.Budget.small();
        var query = QuestionGap.Question.of(q);
        Set<Formula> base = Set.of(Formula.implies(p,q));

        var open = QuestionGap.assess(base, query, QuestionGap.Response.empty(), budget);
        check(open.status() == QuestionGap.Status.OPEN && open.closure() == 0.0,
                "Legacy evaluator describes knowledge status, not the creation of an interrogative gap");
        var draft = new QuestionGap.Response(List.of(
                new QuestionGap.Claim(q, "draft human reply", QuestionGap.Admission.PROPOSED)));
        var proposed = QuestionGap.assess(base, query, draft, budget);
        check(proposed.status() == QuestionGap.Status.OPEN && proposed.ignoredProposals() == 1,
                "An unadmitted reply must not automatically seal a gap");

        var accepted = new QuestionGap.Response(List.of(
                new QuestionGap.Claim(p, "simulated, caller-admitted premise", QuestionGap.Admission.ADMITTED)));
        var complete = QuestionGap.assess(base, query, accepted, budget);
        check(complete.status() == QuestionGap.Status.CLOSED_CONDITIONALLY,
                "Admitted premise can logically determine a question");
        check(complete.closure() == 1.0 && complete.newlyResolved() == 1,
                "Closure and new resolution computed separately");
        check(complete.facets().get(0).after() == ModelExplorer.Status.PROVEN_TRUE,
                "Positive determination correctly recorded");
        check(ModelExplorer.analyze(base,q,budget).status() == ModelExplorer.Status.UNDETERMINED,
                "Gap assessment never mutates original premises");

        var falseAnswer = new QuestionGap.Response(List.of(
                new QuestionGap.Claim(Formula.not(q), "caller-admitted contrary premise", QuestionGap.Admission.ADMITTED)));
        var negative = QuestionGap.assess(Set.of(), query, falseAnswer, budget);
        check(negative.status() == QuestionGap.Status.CLOSED_CONDITIONALLY,
                "An established negative response also resolves yes/no question");
        check(negative.facets().get(0).after() == ModelExplorer.Status.PROVEN_FALSE,
                "Negative resolution is not a positive proof");

        var multi = new QuestionGap.Question(List.of(
                new QuestionGap.Facet("first",q),new QuestionGap.Facet("second",r)));
        var partial = QuestionGap.assess(Set.of(),multi,
                new QuestionGap.Response(List.of(
                        new QuestionGap.Claim(q,"simulated admission",QuestionGap.Admission.ADMITTED))),budget);
        check(partial.status() == QuestionGap.Status.PARTIAL && partial.closure() == .5,
                "Multifacet question can be repaired partially");
        check(partial.facets().get(1).after() == ModelExplorer.Status.UNDETERMINED,
                "Missing facet remains open");
        var all = QuestionGap.assess(Set.of(),multi,
                new QuestionGap.Response(List.of(
                        new QuestionGap.Claim(q,"simulated admission",QuestionGap.Admission.ADMITTED),
                        new QuestionGap.Claim(r,"simulated admission",QuestionGap.Admission.ADMITTED))),budget);
        check(all.status() == QuestionGap.Status.CLOSED_CONDITIONALLY && all.closure() == 1.0,
                "All formally specified facets can be resolved");

        var irrelevant = QuestionGap.assess(Set.of(),query,
                new QuestionGap.Response(List.of(
                        new QuestionGap.Claim(r,"irrelevant assertion",QuestionGap.Admission.ADMITTED))),budget);
        check(irrelevant.status() == QuestionGap.Status.OPEN && irrelevant.newlyResolved() == 0,
                "Unrelated evidence cannot artificially close the question");
        var conflict = QuestionGap.assess(Set.of(p),QuestionGap.Question.of(p),
                new QuestionGap.Response(List.of(
                        new QuestionGap.Claim(Formula.not(p),"conflicting claim",QuestionGap.Admission.ADMITTED))),budget);
        check(conflict.status() == QuestionGap.Status.INCONSISTENT && Double.isNaN(conflict.closure()),
                "Contradiction cannot masquerade as 100 percent repair");
        var exhausted = QuestionGap.assess(Set.of(),query,QuestionGap.Response.empty(),
                new ModelExplorer.Budget(0,1));
        check(exhausted.status() == QuestionGap.Status.RESOURCE_LIMIT && Double.isNaN(exhausted.closure()),
                "Unknown because of budget is not a closed question");
        var already = QuestionGap.assess(Set.of(q),query,QuestionGap.Response.empty(),budget);
        check(already.closure() == 1.0 && already.newlyResolved() == 0,
                "Previously settled question has no new repair due to response");
        expectThrows(IllegalArgumentException.class,
                () -> new QuestionGap.Claim(q,"  ",QuestionGap.Admission.ADMITTED),
                "Admitted claim without traceable source is rejected");
        expectThrows(IllegalArgumentException.class,
                () -> new QuestionGap.Question(List.of()),
                "Question without a facet is not well formed");
        expectThrows(IllegalArgumentException.class,
                () -> new QuestionGap.Question(List.of(
                        new QuestionGap.Facet("x",p),new QuestionGap.Facet("x",q))),
                "Duplicate question facets prohibited");
    }

    public static void interrogativeUniverseTests() {
        Formula p=Formula.atom("p"), q=Formula.atom("q"), r=Formula.atom("r");
        var budget=ModelExplorer.Budget.small();
        var stable = InterrogativeUniverse.stable(Set.of());
        check(stable.snapshot(budget).stable(), "No question: interrogative universe stable");
        check(stable.snapshot(budget).introducedFacets()==0, "No gap exists before question act");
        check(ModelExplorer.analyze(Set.of(), q, budget).status()==ModelExplorer.Status.UNDETERMINED,
                "Knowledge may be indeterminate even while no question has been posed");
        var act = new InterrogativeUniverse.QuestionAct("Q-1", InterrogativeUniverse.Origin.HUMAN,
                QuestionGap.Question.of(q));
        var asked=stable.ask(act);
        check(asked.snapshot(budget).phase()==InterrogativeUniverse.Phase.OPEN,
                "Posing a question introduces an open interrogative gap");
        check(asked.snapshot(budget).introducedFacets()==1 && asked.snapshot(budget).repairRatio()==0.0,
                "Unanswered question creates an obligation rather than revealing pre-existing gap");
        expectThrows(IllegalStateException.class, ()->stable.respond(
                new InterrogativeUniverse.AnswerAct(Map.of("question",true),QuestionGap.Response.empty(),"human")),
                "Cannot give response without prior question");
        expectThrows(IllegalStateException.class, ()->asked.ask(act), "One question per minimal session");
        var merelyProposed=asked.respond(new InterrogativeUniverse.AnswerAct(Map.of("question",true),
                new QuestionGap.Response(List.of(new QuestionGap.Claim(q,"proposal",QuestionGap.Admission.PROPOSED))),"speaker"));
        check(merelyProposed.snapshot(budget).phase()==InterrogativeUniverse.Phase.OPEN,
                "Unadmitted answer does not close gap by assertion alone");
        var answered=asked.respond(new InterrogativeUniverse.AnswerAct(Map.of("question",true),
                new QuestionGap.Response(List.of(new QuestionGap.Claim(q,"simulated caller admission",QuestionGap.Admission.ADMITTED))),"speaker"));
        check(answered.snapshot(budget).phase()==InterrogativeUniverse.Phase.RESTORED_CONDITIONALLY,
                "Explicit supported answer restores relative stability");
        check(answered.snapshot(budget).repairedFacets()==1 && answered.snapshot(budget).stable(),
                "Gap repair records actual answered facets");
        var predetermined=InterrogativeUniverse.stable(Set.of(q)).ask(act);
        check(predetermined.snapshot(budget).phase()==InterrogativeUniverse.Phase.OPEN,
                "Question opens a gap even when its answer was already known");
        check(predetermined.respond(new InterrogativeUniverse.AnswerAct(Map.of("question",true),
                QuestionGap.Response.empty(),"reply from knowledge")).snapshot(budget).stable(),
                "Explicit answer from existing knowledge repairs without new empirical premise");
        check(predetermined.respond(new InterrogativeUniverse.AnswerAct(Map.of("question",false),
                QuestionGap.Response.empty(),"incorrect reply")).snapshot(budget).phase()==InterrogativeUniverse.Phase.OPEN,
                "False answer cannot repair a gap");
        var two=InterrogativeUniverse.stable(Set.of(q)).ask(new InterrogativeUniverse.QuestionAct(
                "Q-2",InterrogativeUniverse.Origin.SYSTEM,new QuestionGap.Question(List.of(
                new QuestionGap.Facet("first",q),new QuestionGap.Facet("second",r)))));
        var partial=two.respond(new InterrogativeUniverse.AnswerAct(Map.of("first",true),
                QuestionGap.Response.empty(),"human"));
        check(partial.snapshot(budget).phase()==InterrogativeUniverse.Phase.PARTIAL
                && partial.snapshot(budget).repairRatio()==0.5, "Partial answer leaves one introduced gap open");
        check(partial.snapshot(budget).outstanding().equals(List.of("second")),
                "Precise unresolved interrogative facet remains");
        var contradict=InterrogativeUniverse.stable(Set.of(p)).ask(new InterrogativeUniverse.QuestionAct(
                "Q-3",InterrogativeUniverse.Origin.HUMAN,QuestionGap.Question.of(p))).respond(
                new InterrogativeUniverse.AnswerAct(Map.of("question",false),
                        new QuestionGap.Response(List.of(new QuestionGap.Claim(
                        Formula.not(p),"simulated conflicting premise",QuestionGap.Admission.ADMITTED))),"speaker"));
        check(contradict.snapshot(budget).phase()==InterrogativeUniverse.Phase.INCONSISTENT
                && Double.isNaN(contradict.snapshot(budget).repairRatio()),
                "Contradiction never counts as stable restoration");
        check(asked.snapshot(new ModelExplorer.Budget(0,1)).phase()==InterrogativeUniverse.Phase.OPEN,
                "Question act does not need semantic exploration");
        var limited=asked.respond(new InterrogativeUniverse.AnswerAct(Map.of("question",true),
                QuestionGap.Response.empty(),"speaker"));
        check(limited.snapshot(new ModelExplorer.Budget(0,1)).phase()==InterrogativeUniverse.Phase.RESOURCE_LIMIT,
                "Resource budget cannot falsely certify restoration");
        expectThrows(IllegalArgumentException.class, ()->asked.respond(new InterrogativeUniverse.AnswerAct(
                Map.of("nonexistent",true),QuestionGap.Response.empty(),"speaker")),
                "Answer for non-existing facet rejected");
    }

    public static void questionAnswerTests() {
        Formula p=Formula.atom("p"), q=Formula.atom("q"), r=Formula.atom("r");
        check(PropositionalInput.parse("p -> q").equals(Formula.implies(p,q)),
                "controlled formula parser handles implication");
        check(PropositionalInput.parse("non p et q").equals(Formula.and(Formula.not(p),q)),
                "negation binds more strongly than conjunction");
        check(PropositionalInput.parse("p et q -> r").equals(Formula.implies(Formula.and(p,q),r)),
                "conjunction binds more strongly than implication");
        check(PropositionalInput.parse("p -> q -> r").equals(Formula.implies(p,Formula.implies(q,r))),
                "right-associative implication");
        check(PropositionalInput.parse("(p -> q) et r").equals(Formula.and(Formula.implies(p,q),r)),
                "parentheses are respected");
        expectThrows(IllegalArgumentException.class, () -> PropositionalInput.parse("p ->"),
                "reject incomplete implication");
        expectThrows(IllegalArgumentException.class, () -> PropositionalInput.parse("p $ q"),
                "reject unsupported syntax");
        expectThrows(IllegalArgumentException.class, () -> PropositionalInput.parse(""),
                "reject missing question");
        CognitiveQuestionService service = new CognitiveQuestionService();
        check(service.ask("q").result() == CognitiveQuestionService.Result.UNKNOWN,
                "without evidence, query cannot invent answer");
        check(service.ask("q").phase() == InterrogativeUniverse.Phase.OPEN,
                "an unanswered question leaves interrogative gap open");
        String pId = service.teach("p", "declaration utilisateur");
        service.teach("p -> q", "declaration utilisateur");
        CognitiveQuestionService.Answer yes = service.ask("q");
        check(yes.result() == CognitiveQuestionService.Result.YES && yes.proofChecked(),
                "derivation yields yes with independently checked proof");
        check(yes.phase() == InterrogativeUniverse.Phase.RESTORED_CONDITIONALLY,
                "an explicit justified answer closes the created question gap");
        service.retract(pId);
        check(service.ask("q").result() == CognitiveQuestionService.Result.UNKNOWN,
                "retracting a premise re-opens a formerly answered question");
        service.teach("non q", "declaration utilisateur");
        check(service.ask("q").result() == CognitiveQuestionService.Result.NO,
                "negative asserted evidence leads to no");
        service.teach("q", "declaration contradictoire");
        check(service.ask("q").result() == CognitiveQuestionService.Result.INCONSISTENT,
                "contradictory context is not published as an answer");
        CognitiveQuestionService small = new CognitiveQuestionService(new ModelExplorer.Budget(1,2));
        small.teach("p -> q", "test");
        check(small.ask("q").result() == CognitiveQuestionService.Result.RESOURCE_LIMIT,
                "calculation bound prevents unjustified response");
        var output = new java.io.ByteArrayOutputStream();
        var input = new java.io.ByteArrayInputStream(
                "sais p\nsais p -> q\n? q\nretire P1\n? q\nquitter\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DialogueConsole.run(input,new java.io.PrintStream(output,true,java.nio.charset.StandardCharsets.UTF_8));
        String cli = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        check(cli.contains("Oui, sous les prémisses") && cli.contains("Je ne peux pas déterminer"),
                "console actually provides answers and re-assesses after retraction");
        check(service.premises().size() == 4,
                "premise history retained including retracted elements");
    }

    /** FCI-003: deliberately adversarial question-relative metrics. */
    public static void questionSemanticsTests() {
        Formula p=Formula.atom("p"), q=Formula.atom("q"), r=Formula.atom("r");
        var budget=ModelExplorer.Budget.small();
        var single=QuestionGap.Question.of(p);
        var empty=QuestionGap.Response.empty();
        var baseline=QuestionSemantics.assess(Set.of(),single,empty,budget);
        check(baseline.status()==QuestionSemantics.Status.EVALUATED
                && baseline.possibleAnswersBefore()==2 && baseline.possibleAnswersAfter()==2,
                "question without answer has two possible yes/no answers");
        check(baseline.answerGainBits()==0.0 && !baseline.questionDetermined(),
                "question act is independent of preexisting epistemic indetermination");
        var unrelated=QuestionSemantics.assess(Set.of(),single,
                new QuestionGap.Response(List.of(new QuestionGap.Claim(r,"simulation",QuestionGap.Admission.ADMITTED))),budget);
        check(unrelated.answerGainBits()==0.0 && !unrelated.questionDetermined(),
                "irrelevant premise reduces worlds but produces zero question-relative gain");
        var direct=QuestionSemantics.assess(Set.of(),single,
                new QuestionGap.Response(List.of(new QuestionGap.Claim(p,"unverified user claim",QuestionGap.Admission.ADMITTED))),budget);
        check(direct.questionDetermined() && Math.abs(direct.answerGainBits()-1.0)<1e-10,
                "asserted answer can conditionally determine q, NOT certify truth");
        check(direct.explanation().contains("not verified") && direct.nonredundantSupportIndices().equals(List.of(0)),
                "echo is conditional only and never treated as independently verified");
        var proposed=QuestionSemantics.assess(Set.of(),single,
                new QuestionGap.Response(List.of(new QuestionGap.Claim(p,"unverified",QuestionGap.Admission.PROPOSED))),budget);
        check(proposed.answerGainBits()==0.0 && !proposed.questionDetermined(),
                "proposed but unadmitted premise cannot close a question");
        var duplicate=QuestionSemantics.assess(Set.of(),
                new QuestionGap.Question(List.of(new QuestionGap.Facet("a",p),
                        new QuestionGap.Facet("b",p),new QuestionGap.Facet("c",Formula.not(p)))),
                new QuestionGap.Response(List.of(new QuestionGap.Claim(p,"simulation",QuestionGap.Admission.ADMITTED))),budget);
        check(Math.abs(direct.answerGainBits()-duplicate.answerGainBits())<1e-10,
                "duplicate/complement facets preserve answer partition and information gain");
        var partial=QuestionSemantics.assess(Set.of(),
                new QuestionGap.Question(List.of(new QuestionGap.Facet("p",p),new QuestionGap.Facet("q",q))),
                new QuestionGap.Response(List.of(new QuestionGap.Claim(p,"simulation",QuestionGap.Admission.ADMITTED))),budget);
        check(partial.possibleAnswersBefore()==4 && partial.possibleAnswersAfter()==2
                && partial.answerGainBits()>0 && !partial.questionDetermined(),
                "partial question gain is gradual without artificial complete closure");
        var withIrrelevant=QuestionSemantics.assess(Set.of(),single,
                new QuestionGap.Response(List.of(new QuestionGap.Claim(p,"simulation",QuestionGap.Admission.ADMITTED),
                        new QuestionGap.Claim(r,"simulation",QuestionGap.Admission.ADMITTED))),budget);
        check(withIrrelevant.nonredundantSupportIndices().equals(List.of(0))
                && withIrrelevant.marginalBits().get(1)==0.0,
                "ablation detects irrelevant admitted claim");
        var duplicatedSupport=QuestionSemantics.assess(Set.of(),single,
                new QuestionGap.Response(List.of(new QuestionGap.Claim(p,"a",QuestionGap.Admission.ADMITTED),
                        new QuestionGap.Claim(p,"b",QuestionGap.Admission.ADMITTED))),budget);
        check(duplicatedSupport.nonredundantSupportIndices().size()==1
                && duplicatedSupport.marginalBits().stream().allMatch(x->x==0.0),
                "single ablation can miss individually redundant duplicate supports");
        var contradiction=QuestionSemantics.assess(Set.of(p),single,
                new QuestionGap.Response(List.of(new QuestionGap.Claim(Formula.not(p),"simulation",QuestionGap.Admission.ADMITTED))),budget);
        check(contradiction.status()==QuestionSemantics.Status.INCONSISTENT
                && Double.isNaN(contradiction.answerGainBits()),
                "no bit gain from contradiction");
        var limited=QuestionSemantics.assess(Set.of(),single,empty,new ModelExplorer.Budget(0,1));
        check(limited.status()==QuestionSemantics.Status.RESOURCE_LIMIT
                && Double.isNaN(limited.answerGainBits()),
                "no fictitious gain when budget exceeded");
        var already=QuestionSemantics.assess(Set.of(p),single,empty,budget);
        check(already.questionDetermined() && already.answerGainBits()==0.0,
                "known answer can already be logically determined yet Q act still opens obligation");
        var logicallyEquivalent=QuestionSemantics.assess(Set.of(),QuestionGap.Question.of(Formula.and(p,p)),
                new QuestionGap.Response(List.of(new QuestionGap.Claim(p,"simulation",QuestionGap.Admission.ADMITTED))),budget);
        check(Math.abs(logicallyEquivalent.answerGainBits()-direct.answerGainBits())<1e-10,
                "logically equivalent single faceting preserves answer gain");
        var monotonic=QuestionSemantics.assess(Set.of(),QuestionGap.Question.of(p),
                new QuestionGap.Response(List.of(new QuestionGap.Claim(p,"source",QuestionGap.Admission.ADMITTED),
                        new QuestionGap.Claim(r,"source",QuestionGap.Admission.ADMITTED))),budget);
        check(monotonic.possibleAnswersAfter()<=direct.possibleAnswersAfter(),
                "coherent addition cannot increase possible answer signatures");
        check(QuestionSemantics.assess(Set.of(),single,empty,budget).possibleAnswersBefore()==2,
                "baseline remains unchanged by response evaluation (no mutation)");
    }

    /** IVOC-FND-004: vérification de l'intégration, y compris scénarios adversariaux. */
    public static void integratedCycleTests() {
        var c = new IntegratedQuestionCycle();
        check(c.questions().isEmpty(), "stability before a question");
        var empty = c.ask("q");
        check(empty.status() == IntegratedQuestionCycle.Status.OPEN && empty.questionGainBits().isPresent(),
                "Q creates an obligation even with no pre-existing uncertainty diagnosis");
        check(empty.answer().isEmpty() && empty.requestedObservation().isEmpty(),
                "cannot invent an answer or an observation");
        check(c.questions().size() == 1 && c.question("Q1").status() == IntegratedQuestionCycle.Status.OPEN,
                "open question persists between turns");
        expectThrows(IllegalArgumentException.class, () -> c.question("MISSING"),
                "unknown question rejected");
        expectThrows(IllegalStateException.class, () -> c.answerObservation("Q1", true, "human"),
                "cannot provide unrequested data as an RDO response");

        var logical = new IntegratedQuestionCycle();
        String a = logical.teach("p", "human");
        logical.teach("p -> q", "human");
        var answered = logical.ask("q");
        check(answered.status() == IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && answered.answer().equals(Optional.of(true)), "known answer still requires act Q and answer");
        check(answered.proofChecked(), "integrated service produces an independently checked proof");
        check(logical.journal().stream().anyMatch(e -> e.operation().equals("QUESTION_POSED"))
                && logical.journal().stream().anyMatch(e -> e.operation().equals("ANSWER_GIVEN")),
                "interrogative acts and repairs are audited");
        logical.retract(a);
        var reopened = logical.question(answered.id());
        check(reopened.status() == IntegratedQuestionCycle.Status.OPEN && reopened.reopened(),
                "retraction reopens the SAME Q, not a newly created question");
        check(reopened.questionGainBits().isEmpty(),
                "initial baseline no longer valid after retraction; no fictitious information gain");
        check(logical.journal().stream().anyMatch(e -> e.operation().equals("QUESTION_REOPENED")),
                "reopening recorded as an event");

        var multi = new IntegratedQuestionCycle();
        multi.teach("b -> c", "declared relation");
        multi.teach("c -> b", "declared relation");
        multi.registerObservable("b", 2);
        multi.registerObservable("z", 1);
        var first = multi.ask("c");
        var second = multi.ask("non c");
        check(first.status() == IntegratedQuestionCycle.Status.NEED_INFORMATION
                && first.requestedObservation().orElseThrow().equals(Formula.atom("b")),
                "RDO favours a question-solving observation despite irrelevant cheaper z");
        check(second.status() == IntegratedQuestionCycle.Status.NEED_INFORMATION
                && multi.questions().size() == 2 && !first.id().equals(second.id()),
                "independent simultaneous questions retained");
        check(multi.journal().stream().noneMatch(e -> e.operation().equals("OBSERVATION_DECLARED")),
                "planning does not fabricate observation");
        var resolved = multi.answerObservation(first.id(), true, "human, not verified");
        check(resolved.status() == IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && resolved.answer().orElseThrow(), "RDO receives declared value and closes first Q");
        check(resolved.proofChecked(), "deduction after declared observation proof-checked");
        check(resolved.questionGainBits().isPresent()
                && Math.abs(resolved.questionGainBits().orElseThrow() - 1.0) < 1e-9,
                "FCI-003 gain is linked to real question session");
        check(multi.question(second.id()).status() == IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && !multi.question(second.id()).answer().orElseThrow(),
                "new data updates other pending question without inventing another query");
        String observedId = multi.premises().get(multi.premises().size() - 1).id();
        multi.retract(observedId);
        check(multi.question(first.id()).reopened() && multi.question(second.id()).reopened(),
                "retraction reopens all dependent interrogative obligations");
        check(multi.question(first.id()).requestedObservation().orElseThrow().equals(Formula.atom("b")),
                "pending RDO investigation reappears after withdrawal");
        check(multi.question(first.id()).questionGainBits().isPresent()
                && multi.question(first.id()).questionGainBits().orElseThrow() == 0.0,
                "question-relative gain correctly falls back after withdrawal");
        var negative = multi.answerObservation(first.id(), false, "human, not verified");
        check(negative.answer().equals(Optional.of(false)) && negative.proofChecked(),
                "negative observation supports negative response, with checked proof");
        check(multi.question(second.id()).answer().equals(Optional.of(true)),
                "complementary question gets positive answer after negative branch");
        check(multi.premises().stream().anyMatch(p -> p.kind() == KnowledgeBase.Kind.OBSERVATION),
                "observation recorded as declared source with a distinct kind");
        expectThrows(IllegalStateException.class,
                () -> multi.answerObservation(first.id(), true, "cannot overrule known outcome"),
                "observation cannot be added when no investigation is pending");
        expectThrows(IllegalArgumentException.class, () -> multi.answerObservation("NONE", true, "x"),
                "unknown question cannot accept evidence");
        expectThrows(IllegalArgumentException.class, () -> multi.answerObservation(second.id(), true, ""),
                "observation source mandatory");

        var contradictory = new IntegratedQuestionCycle();
        contradictory.teach("p", "source");
        contradictory.teach("non p", "source");
        check(contradictory.ask("p").status() == IntegratedQuestionCycle.Status.INCONSISTENT,
                "contradiction never repairs a question");
        var limited = new IntegratedQuestionCycle(new ModelExplorer.Budget(1,2));
        limited.teach("p -> q", "human");
        check(limited.ask("q").status() == IntegratedQuestionCycle.Status.RESOURCE_LIMIT,
                "global computational budget respected");
        var badCandidate = new IntegratedQuestionCycle(new ModelExplorer.Budget(1,2));
        badCandidate.registerObservable("r", 1);
        check(badCandidate.ask("q").status() == IntegratedQuestionCycle.Status.RESOURCE_LIMIT,
                "catalog cannot silently expand world space beyond budget");
        expectThrows(IllegalArgumentException.class, () -> c.registerObservable("p", 0),
                "zero-cost observation rejected");
        var output = new java.io.ByteArrayOutputStream();
        var input = new java.io.ByteArrayInputStream((
                "sais b -> c\nsais c -> b\nobservable b\n? c\nquestions\nobserve Q1 oui\nquestions\nretire P3\nquestions\nquitter\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DialogueConsole.run(input, new java.io.PrintStream(output, true, java.nio.charset.StandardCharsets.UTF_8));
        String cli = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        check(cli.contains("Peux-tu observer") && cli.contains("RESTORED_CONDITIONALLY")
                && cli.contains("faille rouverte"),
                "CLI really supports multi-turn answer and reopening after retraction");
    }

    /** 60 small predetermined cases across distinct epistemic states; no held-out natural language benchmark. */
    public static void scenarioMatrixTests() {
        for (int i = 0; i < 10; i++) {
            String p = "p" + i, q = "q" + i;
            var cycle = new IntegratedQuestionCycle();
            cycle.teach(p, "matrix");
            cycle.teach(p + " -> " + q, "matrix");
            check(cycle.ask(q).answer().equals(Optional.of(true)), "matrix: positive deduction " + i);
            check(cycle.question("Q1").proofChecked(), "matrix: positive proof " + i);
        }
        for (int i = 0; i < 10; i++) {
            var cycle = new IntegratedQuestionCycle();
            cycle.teach("non p" + i, "matrix");
            check(cycle.ask("p" + i).answer().equals(Optional.of(false)), "matrix: negative deduction " + i);
        }
        for (int i = 0; i < 10; i++) {
            var cycle = new IntegratedQuestionCycle();
            check(cycle.ask("p" + i).status() == IntegratedQuestionCycle.Status.OPEN,
                    "matrix: unknown remains open " + i);
        }
        for (int i = 0; i < 10; i++) {
            String b="b"+i, q="q"+i;
            var cycle = new IntegratedQuestionCycle();
            cycle.teach(b+" -> "+q, "matrix");
            cycle.teach(q+" -> "+b, "matrix");
            cycle.registerObservable(b, 1);
            var ask = cycle.ask(q);
            check(ask.status() == IntegratedQuestionCycle.Status.NEED_INFORMATION,
                    "matrix: RDO interrogates observation " + i);
            check(cycle.answerObservation(ask.id(), true, "matrix declaration").answer().equals(Optional.of(true)),
                    "matrix: new declared information closes Q " + i);
        }
        for (int i = 0; i < 10; i++) {
            String b="b"+i, q="q"+i;
            var cycle = new IntegratedQuestionCycle();
            cycle.teach(b+" -> "+q, "matrix");
            cycle.teach(q+" -> "+b, "matrix");
            cycle.registerObservable(b, 1);
            var ask = cycle.ask(q);
            check(cycle.answerObservation(ask.id(), false, "matrix declaration").answer().equals(Optional.of(false)),
                    "matrix: negative branch correct " + i);
        }
        for (int i = 0; i < 10; i++) {
            String p="p"+i, q="q"+i;
            var cycle = new IntegratedQuestionCycle();
            String id=cycle.teach(p,"matrix");
            cycle.teach(p+" -> "+q, "matrix");
            var asked=cycle.ask(q);
            check(asked.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                    "matrix: conditional initial response " + i);
            cycle.retract(id);
            check(cycle.question(asked.id()).reopened(),
                    "matrix: retract reopens existing question " + i);
        }
    }


    /** Revue croisée 0.4.1 : contre-exemples et chemins non couverts en 0.4.0. */
    public static void crossReviewTests() {
        Formula a = Formula.atom("a"), b = Formula.atom("b"), c = Formula.atom("c");
        var premises = Set.of(Formula.implies(a, b), Formula.not(b));
        var negativeProof = ProofSearch.find(premises, Formula.not(a), 200);
        check(negativeProof.isPresent(), "modus tollens constructible through ->I");
        check(negativeProof.isPresent() && ProofChecker.verify(negativeProof.get(), premises).valid(),
                "modus tollens proof independently checked");

        var direct = new IntegratedQuestionCycle();
        direct.teach("c", "simple déclaration humaine");
        var directAnswer = direct.ask("c");
        check(directAnswer.status() == IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "unverified direct answer never restores verified status");
        check(directAnswer.support() == IntegratedQuestionCycle.Support.DIRECT_UNVERIFIED,
                "direct unverified answer is explicitly labelled");
        check(directAnswer.status() != IntegratedQuestionCycle.Status.RESTORED_JUSTIFIED,
                "no empirical source verifier currently enables justified closure");
        var derived = new IntegratedQuestionCycle();
        derived.teach("a", "assertion sans preuve empirique");
        derived.teach("a -> b", "assertion sans preuve empirique");
        var derivedAnswer = derived.ask("b");
        check(derivedAnswer.proofChecked(), "formal proof is available");
        check(derivedAnswer.support() == IntegratedQuestionCycle.Support.PROOF_CONDITIONAL,
                "proof does not validate empirical premises");
        check(derivedAnswer.status() == IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "proof from unchecked sources remains conditional");

        Formula x1=Formula.atom("x1"), x2=Formula.atom("x2"), x3=Formula.atom("x3"), target=Formula.atom("parity");
        Formula parity = xor(xor(x1,x2),x3);
        var plan = RdoPlanner.propose(Set.of(Formula.iff(target,parity)), target,
                List.of(new RdoPlanner.Candidate(x1,1), new RdoPlanner.Candidate(x2,1),
                        new RdoPlanner.Candidate(x3,1)), ModelExplorer.Budget.small());
        check(plan.analysis().status()==ModelExplorer.Status.UNDETERMINED,
                "XOR parity is not already determined");
        check(plan.plannedSequence().size()==3,
                "RDO bounded AND/OR lookahead identifies 3-observation parity plan");
        check(plan.choice().isPresent() && plan.choice().orElseThrow().guaranteesQuestion()==0,
                "a zero-immediate-gain observation can belong to a complete plan");

        var decline = new IntegratedQuestionCycle();
        decline.registerObservable("x1", 1);
        decline.registerObservable("x2", 1);
        var ask = decline.ask("x1 et x2");
        check(ask.status()==IntegratedQuestionCycle.Status.NEED_INFORMATION,
                "initial RDO requests a declared observation");
        Formula first = ask.requestedObservation().orElseThrow();
        var afterDecline = decline.declineObservation(ask.id());
        check(afterDecline.requestedObservation().isEmpty()
                || !afterDecline.requestedObservation().orElseThrow().equals(first),
                "declined observation is not repeated");
        check(decline.journal().stream().anyMatch(e -> e.operation().equals("OBSERVATION_DECLINED")),
                "decline is audit logged");
        check(decline.question(ask.id()).requestedObservation().equals(afterDecline.requestedObservation()),
                "unchanged knowledge does not resurrect declined request");

        var alternatives = new IntegratedQuestionCycle();
        String pId = alternatives.teach("a", "source 1");
        alternatives.teach("a -> c", "source 1");
        alternatives.teach("b", "source 2");
        alternatives.teach("b -> c", "source 2");
        var initial = alternatives.ask("c");
        check(initial.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "multiple independent derivations restore Q conditionally");
        alternatives.retract(pId);
        var remaining = alternatives.question(initial.id());
        check(remaining.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && !remaining.reopened(), "alternative proof retains closure after one premise removed");
        check(alternatives.journal().stream().noneMatch(e -> e.questionId().equals(initial.id())
                && e.operation().equals("QUESTION_REOPENED")), "no false reopening event");
    }

    private static Formula xor(Formula left, Formula right) {
        return Formula.and(Formula.implies(left,Formula.not(right)),
                Formula.implies(Formula.not(left),right));
    }


    /** Fixed-seed metamorphic sequences: incremental question updates vs independent truth-table oracle. */
    public static void incrementalOracleTests() {
        java.util.Random random = new java.util.Random(20261011L);
        String[] formulas = {"a", "b", "c", "non a", "non b", "a -> b", "b -> c", "a et b"};
        for (int run = 0; run < 40; run++) {
            IntegratedQuestionCycle cycle = new IntegratedQuestionCycle();
            cycle.ask("a"); cycle.ask("b");
            List<String> activeIds = new ArrayList<>();
            for (int step = 0; step < 8; step++) {
                if (!activeIds.isEmpty() && random.nextBoolean()) {
                    String removed = activeIds.remove(random.nextInt(activeIds.size()));
                    cycle.retract(removed);
                } else {
                    String next = formulas[random.nextInt(formulas.length)];
                    activeIds.add(cycle.teach(next,"oracle fixed-seed"));
                }
                Set<Formula> k = new LinkedHashSet<>();
                for (var premise : cycle.premises()) if (premise.active()) k.add(premise.formula());
                for (String atom : List.of("a","b")) {
                    boolean any = false, yes = false, no = false;
                    for (int mask = 0; mask < 8; mask++) {
                        var values = Map.of("a",(mask & 1)!=0,"b",(mask & 2)!=0,"c",(mask & 4)!=0);
                        boolean compatible = true;
                        for (Formula term : k) if (!term.evaluate(values)) { compatible=false; break; }
                        if (!compatible) continue;
                        any = true;
                        if (values.get(atom)) yes = true; else no = true;
                    }
                    IntegratedQuestionCycle.Report report = cycle.question(atom.equals("a")?"Q1":"Q2");
                    IntegratedQuestionCycle.Status expected = !any
                        ? IntegratedQuestionCycle.Status.INCONSISTENT
                        : yes && no ? IntegratedQuestionCycle.Status.OPEN
                        : IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY;
                    check(report.status()==expected,
                            "incremental matches independent finite oracle run="+run+" step="+step+" q="+atom);
                    if (report.answer().isPresent())
                        check(report.answer().get()==(yes && !no),
                                "answer sign equals truth-table oracle");
                }
            }
            List<IntegratedQuestionCycle.Event> log = cycle.journal();
            for (int i=0;i<log.size();i++)
                check(log.get(i).number()==i+1L,"append-only journal consecutive sequence");
        }
    }

    /** IVOC-FND-004-PROV: checked logical proof is not empirical source certification. */
    public static void provenanceTests() {
        var budget = ModelExplorer.Budget.small();
        Formula p = Formula.atom("p"), q = Formula.atom("q"), r = Formula.atom("r");
        IntegratedQuestionCycle chain = new IntegratedQuestionCycle(budget);
        String pId = chain.teach("p", "LABORATOIRE CERTIFIÉ (texte libre, NON VÉRIFIÉ)");
        String pqId = chain.teach("p -> q", "Déclaration A");
        String qrId = chain.teach("q -> r", "Déclaration B");
        var answer = chain.ask("r");
        check(answer.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "untrusted premises cannot elevate closure despite chain of verified deductions");
        check(answer.support()==IntegratedQuestionCycle.Support.PROOF_CONDITIONAL && answer.proofChecked(),
                "derivation checked but empirically conditional");
        ProvenanceAudit.Certificate cert = answer.certificate().orElseThrow();
        check(cert.basis()==ProvenanceAudit.Basis.PROOF_CONDITIONAL,
                "cert differentiates syntax from external world verification");
        check(cert.formalProof().isPresent() && ProofChecker.verify(
                cert.formalProof().orElseThrow(), chain.premises().stream()
                        .filter(KnowledgeBase.Premise::active)
                        .map(KnowledgeBase.Premise::formula).toList()).valid(),
                "certificate contains independently checkable proof tree");
        check(cert.dependencies().size()==3, "transitive deduction retains three prerequisite formulas");
        Set<String> supportingIds = new HashSet<>();
        for (var dependency : cert.dependencies())
            for (var alternative : dependency.alternativeActiveSources())
                supportingIds.add(alternative.id());
        check(supportingIds.equals(Set.of(pId,pqId,qrId)),
                "explicit lineage records precisely the asserted premise IDs");
        check(!cert.hasEmpiricallyVerifiedPremises() && !cert.hasPremiseFreeFormalCertificate(),
                "source spelling cannot spoof certification or a theorem");
        check(chain.isCertificateCurrent(answer.id(), cert), "certificate current before revision");
        chain.retract(pId);
        var afterRetraction = chain.question(answer.id());
        check(afterRetraction.status()!=IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && afterRetraction.certificate().isEmpty() && afterRetraction.reopened(),
                "retracting root premise reopens and invalidates stale lineage certificate");
        check(!chain.isCertificateCurrent(answer.id(), cert),
                "stored lineage snapshot must not pass currency check after retraction");

        // If a premise is supplied by two different sources, both are listed as
        // alternatives, not falsely made mandatory at the same time.
        IntegratedQuestionCycle duplicate = new IntegratedQuestionCycle();
        String a = duplicate.teach("p", "source A");
        String b = duplicate.teach("p", "source B");
        duplicate.teach("p -> q", "rule");
        var report = duplicate.ask("q");
        var pDependency = report.certificate().orElseThrow().dependencies().stream()
                .filter(d -> d.formula().equals(p)).findFirst().orElseThrow();
        check(pDependency.alternativeActiveSources().size()==2,
                "duplicate formula preserves disjunctive alternatives by source IDs");
        check(pDependency.alternativeActiveSources().stream().map(ProvenanceAudit.Source::id)
                .collect(java.util.stream.Collectors.toSet()).equals(Set.of(a,b)),
                "all active alternative sources displayed");
        duplicate.retract(a);
        var surviving = duplicate.question(report.id());
        check(surviving.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && surviving.certificate().orElseThrow().dependencies().stream()
                  .filter(d->d.formula().equals(p)).findFirst().orElseThrow()
                    .alternativeActiveSources().size()==1,
                "source removal updates lineage without reopening when other ID is active");

        // A previously direct question can be independently derived from
        // other declarations: prefer the non-circular support certificate.
        IntegratedQuestionCycle redundant = new IntegratedQuestionCycle();
        redundant.teach("p", "direct question assertion");
        redundant.teach("q", "external supporting observation");
        redundant.teach("q -> p", "rule");
        var redundantReport = redundant.ask("p");
        var redundantCert = redundantReport.certificate().orElseThrow();
        check(redundantCert.basis()==ProvenanceAudit.Basis.PROOF_CONDITIONAL,
                "avoid question-copy provenance when independent derivation exists");
        check(redundantCert.dependencies().stream().noneMatch(d->d.formula().equals(p)),
                "independent derivation does not use p merely because p was asserted");

        // A proof with NO externally trusted assumptions can be a theorem of
        // the calculus, which still establishes no empirical observation.
        IntegratedQuestionCycle formal = new IntegratedQuestionCycle();
        var theorem = formal.ask("p -> p");
        check(theorem.status()==IntegratedQuestionCycle.Status.RESTORED_FORMALLY,
                "premise-free logical theorem gets formal status, not empirical certification");
        check(theorem.support()==IntegratedQuestionCycle.Support.FORMAL_THEOREM,
                "theorem does not masquerade as independently verified observation");
        check(theorem.certificate().orElseThrow().dependencies().isEmpty()
                && theorem.certificate().orElseThrow().hasPremiseFreeFormalCertificate(),
                "formal theorem requires checked proof and zero active external premises");
        check(!theorem.certificate().orElseThrow().hasEmpiricallyVerifiedPremises(),
                "formal theorem implies no physical fact on its own");
        check(formal.isCertificateCurrent(theorem.id(), theorem.certificate().orElseThrow()),
                "proof certificate attached to current state");

        IntegratedQuestionCycle direct = new IntegratedQuestionCycle();
        direct.teach("p", "source claiming to be independently verified");
        var asserted = direct.ask("p");
        check(asserted.support()==IntegratedQuestionCycle.Support.DIRECT_UNVERIFIED
                && asserted.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "direct copied answer never becomes empirically verified");
        check(asserted.certificate().orElseThrow().dependencies().size()==1,
                "direct answer identifies its source premise");
        check(!asserted.certificate().orElseThrow().hasEmpiricallyVerifiedPremises(),
                "source label is not empirical evidence");

        // Structured negative proof under classical finite model semantics.
        IntegratedQuestionCycle negative = new IntegratedQuestionCycle();
        negative.teach("p -> q", "rule assertion");
        negative.teach("non q", "negative observation declared");
        var neg = negative.ask("p");
        check(neg.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY
                && neg.answer().equals(Optional.of(false)), "modus tollens leads to conditional NO");
        check(neg.proofChecked() && neg.certificate().orElseThrow().dependencies().size()==2,
                "negative proof trace includes all undischarged declarations");

        check(ProvenanceAudit.inspect(r, Set.of(p), List.of(), 0, 50, budget).basis()
                        == ProvenanceAudit.Basis.NOT_ESTABLISHED,
                "public certificate interface must not approve a non-entailed goal");
        check(ProvenanceAudit.inspect(p, Set.of(p, Formula.not(p)), List.of(), 0, 50, budget).basis()
                        == ProvenanceAudit.Basis.NOT_ESTABLISHED,
                "inconsistent context never mints lineage certificate");
        expectThrows(IllegalArgumentException.class,
                ()->ProvenanceAudit.inspect(p, Set.of(p), List.of(), 0, 50, budget),
                "cannot forge lineage with no active premise metadata");
        expectThrows(IllegalArgumentException.class,
                ()->ProvenanceAudit.inspect(p, Set.of(p), List.of(), 0, 0, budget),
                "invalid proof budget rejected");
    }


    /** Revue croisée 0.4.2 : supports alternatifs, référence à Q, révisions. */
    public static void review043Tests() {
        var budget = ModelExplorer.Budget.small();
        Formula p = Formula.atom("p"), q = Formula.atom("q"), r = Formula.atom("r"), z = Formula.atom("z");
        Formula pToQ = Formula.implies(p,q), rToQ = Formula.implies(r,q);
        var sup = SupportAnalyzer.find(Set.of(p,r,pToQ,rToQ,z), q, budget, 512, 20);
        check(sup.status() == SupportAnalyzer.Status.COMPLETE, "exhaustive limited support search");
        check(sup.minimalSupports().size() == 2, "two independent minimally sufficient supports");
        check(sup.minimalSupports().contains(Set.of(p,pToQ)), "first minimal support");
        check(sup.minimalSupports().contains(Set.of(r,rToQ)), "second minimal support");
        check(sup.minimalSupports().stream().noneMatch(s -> s.contains(z)), "irrelevant premise never required");
        for (Set<Formula> support : sup.minimalSupports()) {
            check(ModelExplorer.analyze(support,q,budget).status()==ModelExplorer.Status.PROVEN_TRUE,
                    "each support actually entails goal");
            for (Formula member : support) {
                Set<Formula> reduced = new LinkedHashSet<>(support);
                reduced.remove(member);
                check(ModelExplorer.analyze(reduced,q,budget).status()!=ModelExplorer.Status.PROVEN_TRUE,
                        "each support is inclusion-minimal");
            }
        }
        check(SupportAnalyzer.find(Set.of(p,Formula.not(p)),q,budget,200,8).status()
                ==SupportAnalyzer.Status.INCONSISTENT,"incoherent premises never certify a conclusion");
        check(SupportAnalyzer.find(Set.of(p,pToQ),q,budget,1,8).status()
                ==SupportAnalyzer.Status.SEARCH_LIMIT,"partial search marked incomplete");
        check(SupportAnalyzer.find(Set.of(p),q,budget,50,8).status()
                ==SupportAnalyzer.Status.NOT_ENTAILED,"unknown is not entailed");

        IntegratedQuestionCycle cycle = new IntegratedQuestionCycle();
        String idP = cycle.teach("p", "declaration p");
        String idR = cycle.teach("r", "declaration r");
        cycle.teach("p -> q", "rule 1");
        cycle.teach("r -> q", "rule 2");
        var report = cycle.ask("q");
        check(report.certificate().orElseThrow().alternativeSupports().minimalSupports().size()==2,
                "question certificate records both independent supports");
        cycle.retract(idP);
        check(cycle.question(report.id()).status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "alternative support survives premise retraction");
        cycle.retract(idR);
        check(cycle.question(report.id()).reopened(),"removing last independent support reopens question");

        Formula pOrQ = Formula.implies(Formula.not(p),q);
        var binary = new QuestionPartition.Definition(List.of(
                new QuestionPartition.Cell("oui",pOrQ),
                new QuestionPartition.Cell("non",Formula.not(pOrQ))));
        var detailed = new QuestionPartition.Definition(List.of(
                new QuestionPartition.Cell("p_et_q", Formula.and(p,q)),
                new QuestionPartition.Cell("p_seul", Formula.and(p,Formula.not(q))),
                new QuestionPartition.Cell("q_seul", Formula.and(Formula.not(p),q)),
                new QuestionPartition.Cell("aucun", Formula.and(Formula.not(p),Formula.not(q)))));
        check(QuestionPartition.valid(binary,budget),"yes-no partition valid");
        check(QuestionPartition.valid(detailed,budget),"four-way partition valid");
        check(QuestionPartition.evaluate(binary,Set.of(p),budget).status()==QuestionPartition.Status.DECIDED,
                "p determines the yes/no question");
        check(QuestionPartition.evaluate(detailed,Set.of(p),budget).status()==QuestionPartition.Status.OPEN,
                "same premise does not determine which p/q alternative");
        expectThrows(IllegalArgumentException.class,()->new QuestionPartition.Definition(List.of(
                new QuestionPartition.Cell("only",p))),"single-cell partition rejected");
        check(!QuestionPartition.valid(new QuestionPartition.Definition(List.of(
                new QuestionPartition.Cell("p",p),new QuestionPartition.Cell("also_p",p))),budget),
                "overlapping non-partition rejected");
        IntegratedQuestionCycle partitionCycle = new IntegratedQuestionCycle();
        String premiseP = partitionCycle.teach("p", "source p");
        var simple = partitionCycle.askPartition(binary.cells());
        var complex = partitionCycle.askPartition(detailed.cells());
        check(simple.status()==QuestionPartition.Status.DECIDED && simple.answerCell().equals(Optional.of("oui")),
                "actual Q act creates binary obligation and resolves it");
        check(complex.status()==QuestionPartition.Status.OPEN,
                "actual Q act creates distinct multiway obligation");
        String premiseQ = partitionCycle.teach("q","source q");
        check(partitionCycle.partitionQuestion(complex.id()).answerCell().equals(Optional.of("p_et_q")),
                "new declared information resolves multiway partition");
        partitionCycle.retract(premiseQ);
        check(partitionCycle.partitionQuestion(complex.id()).reopened(),
                "removing q reopens multiway question");
        partitionCycle.retract(premiseP);
        check(partitionCycle.partitionQuestion(simple.id()).reopened(),
                "removing p reopens binary question");
        check(QuestionPartition.evaluate(detailed,Set.of(p,Formula.not(p)),budget).status()
                ==QuestionPartition.Status.INCONSISTENT,"contradiction never determines a partition");
    }

    public static void observation043Tests() {
        var now = java.time.Instant.parse("2026-10-11T19:00:00Z");
        var end = now.plusSeconds(3600);
        Formula.Atom p=(Formula.Atom)Formula.atom("p");
        ObservationLedger ledger=new ObservationLedger();
        var captured=ledger.capture("source-humaine-A",p,true,"calibration-declaree-1",now,end);
        check(ledger.check(captured.id(),now)==ObservationLedger.Status.RECORD_INTEGRITY_OK,
                "SHA256 integrity of raw reading and declared calibration");
        check(captured.interpreted().equals(p),"raw reading maps to p under declared calibration");
        var tampered = new ObservationLedger.Reading(captured.id(),captured.sourceId(),p,false,
                captured.calibrationId(),captured.capturedAt(),captured.expiresAt(),captured.digest());
        check(ObservationLedger.checkDetached(tampered,now)==ObservationLedger.Status.CORRUPT,
                "changed raw reading cannot reuse old digest");
        check(ledger.check(captured.id(),end)==ObservationLedger.Status.EXPIRED,
                "temporal validity expires exactly at expiry instant");
        ledger.revoke(captured.id(),"calibration remise en cause");
        check(ledger.check(captured.id(),now)==ObservationLedger.Status.REVOKED,
                "revocation is authoritative over integrity");
        check(ledger.journal().size()==2 && ledger.journal().get(1).action().equals("REVOKED"),
                "ledger only appends audit events");

        var cycle = new IntegratedQuestionCycle();
        cycle.teach("b -> c","relation conditionnelle fournie");
        cycle.registerObservable("b",1);
        var open=cycle.ask("c");
        check(open.status()==IntegratedQuestionCycle.Status.NEED_INFORMATION &&
                open.requestedObservation().equals(Optional.of(Formula.atom("b"))),
                "RDO identifies required observable");
        var entry=cycle.captureObservation("lecteur-declare",(Formula.Atom)Formula.atom("b"),true,
                "cal-v1",now,end);
        var answered=cycle.answerRecordedObservation(open.id(),entry.id(),now.plusSeconds(60));
        check(answered.status()==IntegratedQuestionCycle.Status.RESTORED_CONDITIONALLY,
                "recorded reading resolves question CONDITIONALLY only");
        check(answered.support()!=IntegratedQuestionCycle.Support.INDEPENDENTLY_VERIFIED,
                "hash integrity and calibration label never certify empirical truth");
        check(answered.certificate().orElseThrow().dependencies().stream().anyMatch(d->d.formula().equals(Formula.atom("b"))),
                "recorded reading stays an external proof assumption");
        cycle.revokeRecordedObservation(entry.id(),"measurement contested");
        check(cycle.question(open.id()).reopened(),"revocation reopens question");
        check(cycle.observationStatus(entry.id(),now)==ObservationLedger.Status.REVOKED,
                "revoked reading cannot be re-used");
        expectThrows(IllegalStateException.class,()->cycle.answerRecordedObservation(open.id(),entry.id(),now),
                "cannot re-admit revoked observation");
        var negative=cycle.captureObservation("lecteur-declare",(Formula.Atom)Formula.atom("b"),false,
                "cal-v1",now,end);
        check(negative.interpreted().equals(Formula.not(Formula.atom("b"))),
                "false raw reading interpreted as negated proposition");
        var resumed=cycle.answerRecordedObservation(open.id(),negative.id(),now.plusSeconds(1));
        check(resumed.status()==IntegratedQuestionCycle.Status.NEED_INFORMATION
                || resumed.status()==IntegratedQuestionCycle.Status.OPEN,
                "negative b does not prove c from b->c");
        cycle.expireRecordedObservations(end);
        check(cycle.observationStatus(negative.id(),end)==ObservationLedger.Status.EXPIRED,
                "expiration recognized before reuse");
        check(cycle.premises().stream().anyMatch(x->x.source().contains("raw "+negative.id())&&!x.active()),
                "explicit clock update retracts expired recorded premise");
    }

    public static void replacement043Tests() {
        Formula p=Formula.atom("p"), q=Formula.atom("q"), r=Formula.atom("r");
        var kb=new KnowledgeBase();
        kb.addPremise("same",p,"human 1",KnowledgeBase.Kind.ASSERTION);
        kb.recordProof("old-proof",new Proof.Assumption(p));
        check(kb.isProofActive("old-proof"),"initial proof active");
        kb.replacePremise("same",Formula.not(p),"human correction",KnowledgeBase.Kind.ASSERTION);
        check(!kb.isProofActive("old-proof"),"reused ID with negated content never validates old proof");
        check(kb.revisions().size()==2,"replacement creates immutable content-bearing revision");
        check(kb.revisions().get(1).previous().equals(p)
                && kb.revisions().get(1).current().equals(Formula.not(p)),
                "old and new formula preserved");
        int history=kb.revisions().size();
        expectThrows(IllegalArgumentException.class,
                ()->kb.replacePremise("same",q,"",KnowledgeBase.Kind.ASSERTION),
                "invalid replacement rejected atomically");
        check(kb.revisions().size()==history && kb.activePremises().contains(Formula.not(p)),
                "failed replacement cannot partially mutate KB");
        var cycle=new IntegratedQuestionCycle();
        String id=cycle.teach("p","source");
        var asked=cycle.ask("p");
        var old=asked.certificate().orElseThrow();
        cycle.replace(id,"non p","corrected");
        check(!cycle.isCertificateCurrent(asked.id(),old),"old certificate invalidated after content replacement");
        check(cycle.question(asked.id()).answer().equals(Optional.of(false)),
                "replacement revises answer, not just version metadata");
        check(cycle.revisions().get(1).previous().equals(p),"replacement lineage in cycle");
        Formula tautology = Formula.implies(Formula.and(q,p),p);
        var unrelated = ProvenanceAudit.inspect(tautology,Set.of(r),List.of(
                new KnowledgeBase.Premise("u",r,"unrelated",KnowledgeBase.Kind.ASSERTION,true)),
                1,100,ModelExplorer.Budget.small());
        check(unrelated.basis()==ProvenanceAudit.Basis.FORMAL_THEOREM,
                "theorem in presence of unrelated premise is still a theorem");
        check(unrelated.dependencies().isEmpty()
                && unrelated.alternativeSupports().minimalSupports().equals(List.of(Set.of())),
                "theorem support extracted from undischarged leaves: empty set");
        check(ProofChecker.verify(new Proof.ImplicationIntroduction(p,new Proof.Assumption(p)),Set.of(r))
                .dependencies().isEmpty(),"theorem depends on no unrelated active premise");
    }
    public static int runAll() {
        assertions = 0;
        proofTests(); semanticTests(); memoryTests(); rdoTests(); questionGapTests(); interrogativeUniverseTests(); questionAnswerTests(); questionSemanticsTests(); integratedCycleTests(); scenarioMatrixTests(); crossReviewTests(); incrementalOracleTests(); provenanceTests(); review043Tests(); observation043Tests(); replacement043Tests();
        assertions += FrenchControlledTest.runAll();
        assertions += LinguisticCompositionTest.runAll();
        assertions += ReviewFnd005AuditTest.runAll();
        assertions += SemanticLexiconTest.runAll();
        assertions += LexicalReviewTest.runAll();
        assertions += LinguisticClarificationTest.runAll();
        assertions += ScopeAndProofTest.runAll();
        assertions += TemporalSemanticTest.runAll();
        return assertions;
    }
    public static void main(String[] args) {
        System.out.println("SELF-TEST OK - " + runAll() + " assertions");
    }
}
