package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDefault075Test {
    @Test void defaultAndExplicitNativeRequireNoIvOption() {
        assertEquals(LocalAssistantCli.Mode.NATIVE, LocalAssistantCli.modeOf(new String[0]));
        assertEquals(LocalAssistantCli.Mode.NATIVE, LocalAssistantCli.modeOf(new String[]{"--chat"}));
        assertEquals(LocalAssistantCli.Mode.ENRICHED, LocalAssistantCli.modeOf(new String[]{"--chat-iv"}));
        assertEquals(LocalAssistantCli.Mode.OBSERVE, LocalAssistantCli.modeOf(new String[]{"--observe","repo","question"}));
        assertEquals(LocalAssistantCli.Mode.VALIDATE, LocalAssistantCli.modeOf(new String[]{"--propose-validate","repo","Test","question"}));
        for (String[] invalid : List.of(new String[]{"--chat","ignored"}, new String[]{"--observe"},
                new String[]{"--propose-validate","repo","Test"},new String[]{"--automatic-agent"}))
            assertThrows(IllegalArgumentException.class, () -> LocalAssistantCli.modeOf(invalid));
    }
    @Test void requestAndAnswerRemainVerbatimWithoutSystemPolicyOrSecondCall() {
        var captured = new ArrayList<List<ChatMessage>>();
        var nativeResult = new GenerationBackend.Result("  Réponse Qwen\navec espaces.  \n", "fixture", "STOP", 1, -1, 1, 2);
        var conversation = new NativeConversation((messages, partial) -> { captured.add(messages); return nativeResult; }, 4);
        String question = "  :raisonner {\"z\":2,\"a\":1}  ";
        assertSame(nativeResult, conversation.ask(question, ignored -> {}));
        assertEquals(1, captured.size());
        assertEquals(1, captured.getFirst().size());
        assertEquals(question, ((UserMessage)captured.getFirst().getFirst()).singleText());
        assertEquals(nativeResult.text(), ((AiMessage)conversation.history().getLast()).text());
        assertTrue(captured.getFirst().stream().noneMatch(SystemMessage.class::isInstance));
    }
    @Test void nativeLimitResponseIsKeptAndFollowupCanContinueWithoutRepair() {
        var calls = new ArrayList<List<ChatMessage>>();
        var truncated = new GenerationBackend.Result("Début de réponse", "fixture", "LENGTH", 1, -1, 1, 16);
        var conversation = new NativeConversation((messages, partial) -> { calls.add(messages); return truncated; }, 4);
        assertSame(truncated, conversation.ask("Question", ignored -> {}));
        assertEquals(2, conversation.history().size());
        conversation.ask("Continue", ignored -> {});
        assertEquals(2, calls.size());
        assertEquals("Début de réponse", ((AiMessage)calls.getLast().get(1)).text());
    }
    @Test void explicitlyBoundedApiWindowEvictsWholePairsAndResetHasNoHiddenSystemMessage() {
        var answer = new GenerationBackend.Result("Texte", "fixture", "STOP", 1, -1, 1, 1);
        var conversation = new NativeConversation((m,p) -> answer, 5);
        for (int i=0;i<4;i++) conversation.ask("Question "+i, ignored -> {});
        assertEquals(4, conversation.history().size());
        assertEquals("Question 2", ((UserMessage)conversation.history().getFirst()).singleText());
        conversation.reset();
        assertTrue(conversation.history().isEmpty());
    }
    @Test void defaultNativeHistoryDoesNotSilentlyRemoveEarlierInstructions() {
        var calls=new ArrayList<List<ChatMessage>>();
        var answer=new GenerationBackend.Result("Texte","fixture","STOP",1,-1,1,1);
        var conversation=new NativeConversation((messages,partial)->{calls.add(messages);return answer;});
        for(int i=0;i<30;i++)conversation.ask("Instruction "+i,ignored->{});
        assertEquals(60,conversation.history().size());
        assertEquals("Instruction 0",((UserMessage)calls.getLast().getFirst()).singleText());
        assertEquals(59,calls.getLast().size());
    }
    @Test void transportFailureDoesNotRetryOrReplaceTheNativeAnswer() {
        int[] count = {0};
        var conversation = new NativeConversation((m,p) -> { count[0]++; throw new IllegalStateException("moteur indisponible"); }, 4);
        assertThrows(IllegalStateException.class, () -> conversation.ask("Question", ignored -> {}));
        assertEquals(1, count[0]);
        assertTrue(conversation.history().isEmpty());
    }
    @Test void consolePrintsFinalNativeTextExactlyOnceAndCommandsAreNotAgentTriggers() {
        int[] count = {0};
        var answer = new GenerationBackend.Result("  Résultat natif\nexact.  ", "fixture", "STOP", 1, -1, 1, 1);
        var conversation = new NativeConversation((m,p) -> { count[0]++; p.accept("Brouillon partiel"); return answer; }, 4);
        var bytes = new java.io.ByteArrayOutputStream();
        LocalAssistantCli.nativeChat(conversation,new Scanner(":analyser un fichier\n:quitter\n"),new java.io.PrintStream(bytes));
        String display = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(display.contains("Qwen > "+answer.text()+System.lineSeparator()));
        assertFalse(display.contains("Brouillon partiel"));
        assertEquals(1,count[0]);
    }
}
