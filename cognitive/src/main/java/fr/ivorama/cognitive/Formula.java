package fr.ivorama.cognitive;

import java.util.*;

/** Syntaxe propositionnelle immuable ; aucune formule n'implique un fait réel. */
public sealed interface Formula permits Formula.Atom, Formula.Bottom, Formula.And, Formula.Implies {

    record Atom(String name) implements Formula {
        public Atom {
            Objects.requireNonNull(name, "name");
            if (!name.matches("[A-Za-z][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("Invalid atom: " + name);
            }
        }
        @Override public String toString() { return name; }
    }

    enum Bottom implements Formula {
        INSTANCE;
        @Override public String toString() { return "⊥"; }
    }

    record And(Formula left, Formula right) implements Formula {
        public And {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
        @Override public String toString() { return "(" + left + " ∧ " + right + ")"; }
    }

    record Implies(Formula premise, Formula conclusion) implements Formula {
        public Implies {
            Objects.requireNonNull(premise, "premise");
            Objects.requireNonNull(conclusion, "conclusion");
        }
        @Override public String toString() { return "(" + premise + " → " + conclusion + ")"; }
    }

    static Formula atom(String name) { return new Atom(name); }
    static Formula and(Formula a, Formula b) { return new And(a, b); }
    static Formula implies(Formula a, Formula b) { return new Implies(a, b); }
    static Formula not(Formula a) { return new Implies(a, Bottom.INSTANCE); }
    static Formula iff(Formula a, Formula b) {
        return and(implies(a, b), implies(b, a));
    }

    static SortedSet<String> atoms(Formula formula) {
        SortedSet<String> result = new TreeSet<>();
        visit(formula, result);
        return Collections.unmodifiableSortedSet(result);
    }
    private static void visit(Formula f, Set<String> names) {
        if (f instanceof Atom a) { names.add(a.name()); }
        else if (f instanceof And a) { visit(a.left(), names); visit(a.right(), names); }
        else if (f instanceof Implies i) { visit(i.premise(), names); visit(i.conclusion(), names); }
    }

    default boolean evaluate(Map<String,Boolean> assignment) {
        if (this instanceof Atom a) {
            Boolean value = assignment.get(a.name());
            if (value == null) throw new IllegalArgumentException("Missing assignment: " + a.name());
            return value;
        }
        if (this instanceof Bottom) return false;
        if (this instanceof And a) return a.left().evaluate(assignment) && a.right().evaluate(assignment);
        Implies i = (Implies) this;
        return !i.premise().evaluate(assignment) || i.conclusion().evaluate(assignment);
    }
}
