package fr.ivorama.cognitive.generative;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationOutcomeTest {
    @Test void failureDoesNotInventAnAnswerLeakDetailsOrCatchFatalErrors() {
        var outcome=ConversationOutcome.execute(()->{throw new IllegalArgumentException("SECRET_INPUT");});
        assertEquals(ConversationOutcome.Status.INVALID_INPUT,outcome.status());assertNull(outcome.value());assertFalse(outcome.explanation().contains("SECRET"));
        assertThrows(AssertionError.class,()->ConversationOutcome.execute(()->{throw new AssertionError();}));
    }
    @Test void nativeAnswerAndHistoryRemainExactAfterLocalFailure() {
        int[] count={0};GenerationBackend backend=(m,p)->{if(count[0]++==0)throw new IllegalStateException("failure SECRET");return new GenerationBackend.Result("  Réponse complète.\n","fixture","LENGTH",1,1,1,2);};
        var chat=new NativeConversation(backend);assertNotEquals(ConversationOutcome.Status.OK,ConversationOutcome.execute(()->chat.ask("Premier message",x->{})).status());assertTrue(chat.history().isEmpty());
        var ok=ConversationOutcome.execute(()->chat.ask("Deuxième message",x->{}));assertEquals(ConversationOutcome.Status.OK,ok.status());assertEquals("  Réponse complète.\n",ok.value().text());assertEquals(2,chat.history().size());assertEquals(2,count[0]);
    }
    @Test void interactiveCliContinuesWithoutPublishingExceptionContents() {
        int[] count={0};var conversation=new NativeConversation((m,p)->{if(count[0]++==0)throw new IllegalStateException("SECRET_EXCEPTION");return new GenerationBackend.Result("Texte intégral.","fixture","STOP",1,1,1,1);});var bytes=new java.io.ByteArrayOutputStream();
        LocalAssistantCli.nativeChat(conversation,new Scanner("Question un\nQuestion deux\n:quitter\n"),new java.io.PrintStream(bytes));String rendered=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);assertTrue(rendered.contains("INTERNAL_ERROR"));assertTrue(rendered.contains("Texte intégral."));assertFalse(rendered.contains("SECRET_EXCEPTION"));assertEquals(2,count[0]);
    }
    @Test void cyclicExceptionCausesCannotBlockClassification() {
        var first = new IllegalStateException("first");
        var second = new IllegalStateException("second");
        first.initCause(second); second.initCause(first);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
                assertEquals(ConversationOutcome.Status.INTERNAL_ERROR, ConversationOutcome.classify(first)));
    }
}
