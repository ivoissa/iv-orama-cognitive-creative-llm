package fr.ivorama.cognitive;
import java.nio.file.*;
import java.util.*;
/** External development evaluation runner: corpus fixed before its first execution.
 * Not blind, not a measure of general French comprehension. Same runner supports the baseline. */
public final class Bridge0642Evaluation {
 public static void main(String[] args)throws Exception{
  int tp=0,fn=0,fp=0,tn=0;
  System.out.println("id\texpected\tactual\taccepted_chars\tsegment_chars\ttext");
  for(String line:Files.readAllLines(Path.of(args[0]))){
   if(line.isBlank()||line.startsWith("#"))continue;
   String[] f=line.split("\t",3);boolean expected=f[1].equals("ACCEPT");
   var registry=new TextReadingRegistry();var result=registry.read(registry.analyze(f[2]).json());boolean actual=result.complete();
   if(expected){if(actual)tp++;else fn++;}else{if(actual)fp++;else tn++;}
   int covered=result.readings().stream().filter(TextReadingRegistry.Reading::accepted).mapToInt(r->r.end()-r.start()).sum();
   int total=result.readings().stream().mapToInt(r->r.end()-r.start()).sum();
   System.out.println(f[0]+"\t"+f[1]+"\t"+(actual?"ACCEPT":"ABSTAIN")+"\t"+covered+"\t"+total+"\t"+f[2]);
  }
  System.out.println("TP="+tp+" FN="+fn+" FP="+fp+" TN="+tn);
 }
}
