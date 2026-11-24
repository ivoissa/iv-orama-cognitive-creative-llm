package fr.ivorama.cognitive;

import java.util.Objects;

/** Termes de preuve explicites : aucune preuve n'est « vraie » sans vérificateur. */
public sealed interface Proof permits Proof.Assumption, Proof.AndIntroduction,
        Proof.AndLeft, Proof.AndRight, Proof.ImplicationIntroduction,
        Proof.ModusPonens, Proof.BottomElimination {

    record Assumption(Formula formula) implements Proof {
        public Assumption { Objects.requireNonNull(formula); }
    }
    record AndIntroduction(Proof left, Proof right) implements Proof {
        public AndIntroduction { Objects.requireNonNull(left); Objects.requireNonNull(right); }
    }
    record AndLeft(Proof ofConjunction) implements Proof {
        public AndLeft { Objects.requireNonNull(ofConjunction); }
    }
    record AndRight(Proof ofConjunction) implements Proof {
        public AndRight { Objects.requireNonNull(ofConjunction); }
    }
    record ImplicationIntroduction(Formula temporaryAssumption, Proof derivation) implements Proof {
        public ImplicationIntroduction {
            Objects.requireNonNull(temporaryAssumption);
            Objects.requireNonNull(derivation);
        }
    }
    record ModusPonens(Proof antecedent, Proof implication) implements Proof {
        public ModusPonens { Objects.requireNonNull(antecedent); Objects.requireNonNull(implication); }
    }
    record BottomElimination(Proof contradiction, Formula conclusion) implements Proof {
        public BottomElimination { Objects.requireNonNull(contradiction); Objects.requireNonNull(conclusion); }
    }
}
