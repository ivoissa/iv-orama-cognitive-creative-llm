package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;
import java.util.*;
public final class Architecture150SmokeMain {
    public static void main(String[] args) {
        TokenData t = new TokenData(1, "arbre", "NOUN");
        MorphologicalEngine morph = new ConservativeMorphologicalEngine();
        if (morph.analyze(t).hasAgreementEvidence()) throw new AssertionError("genre inventé");
        if (morph.inflect("arbres", morph.analyze(t)).isPresent()) throw new AssertionError("flexion inventée");
        ValidationPipeline p = new ValidationPipeline(List.of(
            c -> c.candidate().isBlank() ? Optional.of("empty") : Optional.empty(),
            c -> c.candidate().equals(c.original().word()) ? Optional.of("same") : Optional.empty()));
        if (!p.accepts(new CandidateValidation(t, "végétal", true))) throw new AssertionError("candidate");
        if (p.accepts(new CandidateValidation(t, "arbre", true))) throw new AssertionError("same");
        if (!p.rejectReason(new CandidateValidation(t, "", true)).orElseThrow().equals("empty")) throw new AssertionError("order");
        System.out.println("Architecture v1.5.0-alpha1: 5/5 assertions OK");
    }
}
