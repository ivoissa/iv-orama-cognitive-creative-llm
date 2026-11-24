package fr.ivorama.cognitive;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Replay input prompts, store actual engine output; no reference answer injected. */
public final class TextContext0638Examples {
 public static void main(String[] args)throws Exception{
  if(args.length!=2)throw new IllegalArgumentException("Usage: TextContext0638Examples prompts.txt output.json");
  var router=new FrenchReasoningRouter(new IntegratedQuestionCycle());var results=new ArrayList<Object>();
  for(String prompt:Files.readAllLines(Path.of(args[0]),StandardCharsets.UTF_8)){
   if(prompt.isBlank())continue;
   var turn=router.respond(prompt);var result=turn.execution().orElseThrow();
   var value=new LinkedHashMap<String,Object>();value.put("prompt",prompt);value.put("status",result.status().name());value.put("output",result.output());
   value.put("fullOutput",result.describe());
   result.discourse().ifPresent(d->value.put("discourse",MiniJson.parse(d.json())));
   results.add(value);Files.writeString(Path.of(args[1]),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(results),StandardCharsets.UTF_8);
   System.out.println(results.size()+" "+result.status()+" : "+prompt);
  }
 }
}
