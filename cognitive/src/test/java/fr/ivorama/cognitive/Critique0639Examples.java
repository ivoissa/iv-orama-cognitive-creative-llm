package fr.ivorama.cognitive;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Same prompts replayable against 0.6.38 and 0.6.39. Local provider is explicit. */
public final class Critique0639Examples {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("cases.json output.json");
  var mapper=new ObjectMapper();var cases=mapper.readTree(Path.of(args[0]).toFile());
  var cycle=new IntegratedQuestionCycle();var router=new FrenchReasoningRouter(cycle,q->List.of());var rows=new ArrayList<Object>();
  for(var item:cases){String prompt=item.get("prompt").asText(),id=item.get("id").asText();int before=cycle.questionActCount();var turn=router.respond(prompt);
   var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("promptCharacters",prompt.length());row.put("promptPrefix",prompt.substring(0,Math.min(prompt.length(),300)));row.put("provider","SIMULATED_EMPTY_DOCUMENTS");
   row.put("questionActsCreated",cycle.questionActCount()-before);
   row.put("status",turn.execution().map(r->r.status().name()).orElse("LOGICAL_PATH"));row.put("output",turn.describe());rows.add(row);
   Files.writeString(Path.of(args[1]),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows));System.out.println(id+" : "+row.get("status")+" ; Q créées = "+row.get("questionActsCreated"));
  }
 }
}
