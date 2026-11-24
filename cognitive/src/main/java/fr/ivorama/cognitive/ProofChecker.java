package fr.ivorama.cognitive;

import java.util.*;

/** Vérification indépendante des arbres de preuve (R1 à R6). */
public final class ProofChecker {
    private ProofChecker() {}
    /** Global node-visit ceiling, in addition to the existing depth ceiling. */
    public static final int DEFAULT_MAX_NODES = 100_000;

    public record Result(boolean valid, Formula conclusion, Set<Formula> dependencies, String explanation) {
        public Result { dependencies = Set.copyOf(dependencies); }
    }
    private record Step(Formula conclusion, Set<Formula> dependencies) {}

    public static Result verify(Proof proof, Collection<Formula> assumptions) {
        return verify(proof, assumptions, DEFAULT_MAX_NODES);
    }

    /** Bounded by *visits*, including repeated subtrees, not just proof depth. */
    public static Result verify(Proof proof, Collection<Formula> assumptions, int maxNodes) {
        Objects.requireNonNull(proof);
        Objects.requireNonNull(assumptions);
        if (maxNodes < 1) throw new IllegalArgumentException("maxNodes must be positive");
        try {
            Step step = check(proof, Set.copyOf(assumptions), 0, new int[]{0}, maxNodes);
            return new Result(true, step.conclusion(), step.dependencies(), "preuve correcte");
        } catch (IllegalArgumentException ex) {
            return new Result(false, null, Set.of(), ex.getMessage());
        }
    }

    private static Step check(Proof proof, Set<Formula> context, int depth,
                              int[] visited, int maxNodes) {
        if (++visited[0] > maxNodes)
            throw new IllegalArgumentException("Proof node-visit budget exceeded");
        if (depth > 1024) throw new IllegalArgumentException("Proof depth exceeds safety limit");
        if (proof instanceof Proof.Assumption a) {
            if (!context.contains(a.formula()))
                throw new IllegalArgumentException("Unauthorized assumption: " + a.formula());
            return new Step(a.formula(), Set.of(a.formula()));
        }
        if (proof instanceof Proof.AndIntroduction a) {
            Step left = check(a.left(), context, depth + 1, visited, maxNodes);
            Step right = check(a.right(), context, depth + 1, visited, maxNodes);
            return new Step(Formula.and(left.conclusion(), right.conclusion()), union(left, right));
        }
        if (proof instanceof Proof.AndLeft a) {
            Step in = check(a.ofConjunction(), context, depth + 1, visited, maxNodes);
            if (!(in.conclusion() instanceof Formula.And pair))
                throw new IllegalArgumentException("AndLeft expects a conjunction");
            return new Step(pair.left(), in.dependencies());
        }
        if (proof instanceof Proof.AndRight a) {
            Step in = check(a.ofConjunction(), context, depth + 1, visited, maxNodes);
            if (!(in.conclusion() instanceof Formula.And pair))
                throw new IllegalArgumentException("AndRight expects a conjunction");
            return new Step(pair.right(), in.dependencies());
        }
        if (proof instanceof Proof.ImplicationIntroduction a) {
            Set<Formula> inside = new HashSet<>(context);
            inside.add(a.temporaryAssumption());
            Step body = check(a.derivation(), inside, depth + 1, visited, maxNodes);
            Set<Formula> dependencies = new HashSet<>(body.dependencies());
            dependencies.remove(a.temporaryAssumption());
            return new Step(Formula.implies(a.temporaryAssumption(), body.conclusion()), dependencies);
        }
        if (proof instanceof Proof.ModusPonens a) {
            Step premise = check(a.antecedent(), context, depth + 1, visited, maxNodes);
            Step implication = check(a.implication(), context, depth + 1, visited, maxNodes);
            if (!(implication.conclusion() instanceof Formula.Implies arrow)
                    || !arrow.premise().equals(premise.conclusion())) {
                throw new IllegalArgumentException("Modus ponens: antecedent mismatch");
            }
            return new Step(arrow.conclusion(), union(premise, implication));
        }
        if (proof instanceof Proof.BottomElimination a) {
            Step contradiction = check(a.contradiction(), context, depth + 1, visited, maxNodes);
            if (!contradiction.conclusion().equals(Formula.Bottom.INSTANCE))
                throw new IllegalArgumentException("Bottom elimination expects ⊥");
            return new Step(a.conclusion(), contradiction.dependencies());
        }
        throw new IllegalArgumentException("Unknown proof term");
    }

    private static Set<Formula> union(Step a, Step b) {
        Set<Formula> out = new HashSet<>(a.dependencies());
        out.addAll(b.dependencies());
        return Set.copyOf(out);
    }
}
