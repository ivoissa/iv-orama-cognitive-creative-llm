package fr.ivorama.cognitive.generative;
import fr.ivorama.cognitive.*;
import java.util.*;
public final class P32SuggestionIntegrationProbe {
    private static int assertions;
    private static void ok(boolean yes,String detail){assertions++;if(!yes)throw new AssertionError(detail);}
    private static final String BASE="La porte A est ouverte.";
    public static void main(String[] args){
        for(String model:List.of("qwen-fixture","openai-fixture","other")){
            var turns=new ArrayList<SuggestedGenerationBackend.AdvisoryTurn>();
            var chunks=new ArrayList<String>();
            GenerationBackend delegate=(m,s)->{
                s.accept("chunk");
                return new GenerationBackend.Result("La porte A n'est pas ouverte.",model,"STOP",5,2,3,4);
            };
            var adapter=new SuggestedGenerationBackend(delegate,BASE,ModelExplorer.Budget.small(),turns::add);
            var answer=adapter.generate(List.of(),chunks::add);
            ok(answer.text().equals("La porte A n'est pas ouverte."),"unchanged output");
            ok(chunks.equals(List.of("chunk")),"unchanged streaming");
            ok(answer.inputTokens()==3 && answer.outputTokens()==4,"metadata unchanged");
            ok(turns.size()==1 && turns.getFirst().advice().isPresent(),"advice delivered");
            var result=turns.getFirst().advice().orElseThrow();
            ok(result.suggestions().getFirst().action()==P32SuggestedCorrections.Action.CHALLENGE,"sourced challenge");
            ok(!result.permitsAutomaticReplacement(),"no auto rewrite");
        }
        for(String finish:List.of("LENGTH","TOOL_EXECUTION","UNKNOWN")){
            var turns=new ArrayList<SuggestedGenerationBackend.AdvisoryTurn>();
            GenerationBackend provider=(m,s)->new GenerationBackend.Result("La porte A est ouverte.","provider",finish,1,1,1,1);
            var d=new SuggestedGenerationBackend(provider,BASE,ModelExplorer.Budget.small(),turns::add);
            d.generate(List.of(),s->{});
            ok(turns.size()==1 && turns.getFirst().advice().isEmpty(),"no advice on unconfirmed output");
        }
        var delivered=new ArrayList<SuggestedGenerationBackend.AdvisoryTurn>();
        GenerationBackend provider=(m,s)->new GenerationBackend.Result("La porte A est ouverte.","model","STOP",1,1,1,1);
        var wrapped=new SuggestedGenerationBackend(provider,BASE,ModelExplorer.Budget.small(),delivered::add);
        var conversation=new NativeConversation(wrapped);
        ok(conversation.ask("bonjour",x->{}).text().equals("La porte A est ouverte."),"conversation unchanged");
        ok(conversation.history().size()==2 && delivered.size()==1,"history unchanged");
        var failure=new SuggestedGenerationBackend(provider,BASE,ModelExplorer.Budget.small(),turn->{throw new IllegalStateException("sink down");});
        ok(failure.generate(List.of(),s->{}).text().equals("La porte A est ouverte."),"sink failure isolated");
        GenerationBackend offline=(m,s)->{throw new IllegalStateException("provider down");};
        try { new SuggestedGenerationBackend(offline,BASE,ModelExplorer.Budget.small(),delivered::add).generate(List.of(),s->{});
            throw new AssertionError("Provider failure hidden"); }
        catch (IllegalStateException e){ok(e.getMessage().equals("provider down"),"provider failure propagated");}
        System.out.println("P32SuggestionIntegrationProbe PASS "+assertions+" assertions");
    }
}
