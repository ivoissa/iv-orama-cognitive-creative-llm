package fr.ivorama.cognitive.generation;

import fr.ivorama.cognitive.agent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests on a controlled, declared model. This does not rate Qwen prose. */
class ReasonedGenerativeGuardTest {
    @TempDir Path root;
    private ReasonedObserverSession.Plan model() {
        return new ReasonedObserverSession.Plan("generate",true,
                Map.of("postgres",Map.of("db",true),"network",Map.of("db",false)),
                List.of(new ReasonedObserverSession.Probe("db","db.txt",1,40,"db=yes","db=no")));
    }
    private ReasonedObserverSession.Result investigate(String content,InvestigationOptions.Goal goal) throws Exception {
        Files.writeString(root.resolve("db.txt"),content);
        return new ReasonedObserverSession(new ReadOnlyWorkspace(root),model(),null,
                new InvestigationOptions(goal,true,true,true)).investigate("Quel diagnostic ?");
    }
    @Test void correctsFalseCauseWithoutRemovingSurroundingProse() throws Exception {
        var review=ReasonedGenerativeGuard.review(model(),investigate("db=yes\n",InvestigationOptions.Goal.DIAGNOSE),
                "Introduction. La cause est network. Conclusion.");
        assertEquals(1,review.edits().size());
        assertTrue(review.suggestion().contains("postgres"));
        assertTrue(review.suggestion().startsWith("Introduction. "));
        assertTrue(review.suggestion().endsWith(" Conclusion."));
    }
    @Test void retainsPreciseAndUnrelatedProse() throws Exception {
        var result=investigate("db=yes\n",InvestigationOptions.Goal.DIAGNOSE);
        String original="La seule hypothèse compatible est postgres. Le texte est bien rédigé.";
        assertEquals(original,ReasonedGenerativeGuard.review(model(),result,original).suggestion());
    }
    @Test void refusesToRepairCorruptedEvidence() throws Exception {
        var original=investigate("db=yes\n",InvestigationOptions.Goal.DIAGNOSE);
        var forged=new ReasonedObserverSession.Result(original.report(),original.finding(),original.steps(),List.of(),
                original.commentaryError(),original.completion());
        var review=ReasonedGenerativeGuard.review(model(),forged,"La cause est network.");
        assertEquals(ReasonedGenerativeGuard.Status.NOT_VERIFIABLE,review.status());
        assertTrue(review.edits().isEmpty());
    }
    @Test void cannotConcludeWithoutProof() throws Exception {
        var review=ReasonedGenerativeGuard.review(model(),investigate("neutral\n",InvestigationOptions.Goal.AUDIT_ALL),
                "La cause est postgres.");
        assertEquals(1,review.edits().size());
        assertTrue(review.suggestion().contains("ne permettent pas de départager"));
    }
    @Test void warnsWithoutDeletingComplexHistoricalStyle() throws Exception {
        var review=ReasonedGenerativeGuard.review(model(),investigate("db=yes\n",InvestigationOptions.Goal.DIAGNOSE),
                "Le contrôle est complet car les deux services répondent, mais un fichier reste illisible.");
        assertEquals(ReasonedGenerativeGuard.Status.ALERT_ONLY,review.status());
        assertEquals(review.original(),review.suggestion());
        assertEquals(1,review.warnings().size());
    }
    @Test void safeOffScope() throws Exception {
        var review=ReasonedGenerativeGuard.review(model(),investigate("db=yes\n",InvestigationOptions.Goal.DIAGNOSE),
                "```java\nLa cause est network.\n```");
        assertEquals(ReasonedGenerativeGuard.Status.NOT_VERIFIABLE,review.status());
        assertTrue(review.edits().isEmpty());
    }
}
