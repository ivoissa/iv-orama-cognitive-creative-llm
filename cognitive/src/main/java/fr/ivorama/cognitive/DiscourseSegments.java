package fr.ivorama.cognitive;
import java.util.*;

/** Segmentation de surface bornée ; n'attribue aucun sens ni vérité au contenu cité. */
public final class DiscourseSegments {
 private DiscourseSegments(){}
 public record Span(int start,int end){}
 public record Scan(List<Span> spans,List<String> unresolved){public Scan{spans=List.copyOf(spans);unresolved=List.copyOf(unresolved);}}
 public static Scan scan(String text){
  Objects.requireNonNull(text);if(text.length()>8000)throw new IllegalArgumentException("Texte au-delà de 8000 caractères");
  var spans=new ArrayList<Span>();var issues=new ArrayList<String>();var stack=new ArrayDeque<Character>();
  int start=0;
  for(int i=0;i<text.length();i++){
   char c=text.charAt(i);
   if(!stack.isEmpty()&&c==stack.peek()){stack.pop();continue;}
   if(!stack.isEmpty()&&stack.peek()!=')'&&c!='«'&&c!='“'&&c!='"')continue; // Le contenu cité reste opaque, notamment ses parenthèses.
   if(c=='«'||c=='“'||c=='"'||c=='('){if(stack.size()>=16){issues.add("Imbrication au-delà de 16 niveaux");return new Scan(List.of(new Span(0,text.length())),issues);}stack.push(c=='«'?'»':c=='“'?'”':c=='('?')':'"');continue;}
   if(c=='»'||c=='”'||c==')')issues.add("Délimiteur fermant sans ouverture à "+i);
   if(stack.isEmpty()&&".!?;\n".indexOf(c)>=0){add(spans,text,start,i);start=i+1;}
  }
  add(spans,text,start,text.length());
  if(!stack.isEmpty())issues.add("Citation ou parenthèse non fermée");
  if(spans.size()>32){issues.add("Plus de 32 segments");return new Scan(List.of(new Span(0,text.length())),issues);}
  return new Scan(spans,issues);
 }
 private static void add(List<Span> spans,String text,int a,int b){while(a<b&&Character.isWhitespace(text.charAt(a)))a++;while(b>a&&Character.isWhitespace(text.charAt(b-1)))b--;if(a<b)spans.add(new Span(a,b));}
}
