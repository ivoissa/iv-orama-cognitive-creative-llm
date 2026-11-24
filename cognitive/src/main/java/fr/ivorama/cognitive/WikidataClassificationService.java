package fr.ivorama.cognitive;
import java.util.*;
import static fr.ivorama.cognitive.WikidataSource.*;

/** Classifications documentaires locales au même acte Q ; cache et révisions ne ferment pas la FCI. */
public final class WikidataClassificationService {
 public record Request(String subject,String target){
  public Request{if(subject==null||target==null||subject.isBlank()||target.isBlank()||subject.length()>120||target.length()>120)throw new IllegalArgumentException("Deux termes ou identifiants Q de 120 caractères maximum requis");}
  public String json(){return StructuredJsonWriter.json(Map.of("schema","ivoc.wikidata-classification.1","subject",subject,"target",target));}
  public static Request read(String json){if(json==null||json.length()>2000)throw new IllegalArgumentException("Requête Wikidata hors budget");Object raw=MiniJson.parse(json);if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(Set.of("schema","subject","target"))||!"ivoc.wikidata-classification.1".equals(m.get("schema"))||!(m.get("subject") instanceof String s)||!(m.get("target") instanceof String t))throw new IllegalArgumentException("Contrat Wikidata invalide");return new Request(s,t);}
 }
 public record Answer(String questionId,String state,String subject,String target,List<Candidate> candidates,List<Snapshot> documents,JenaClassificationEngine.Result reasoning,List<String> warnings,Set<String> excluded){
  public Answer{candidates=List.copyOf(candidates);documents=List.copyOf(documents);warnings=List.copyOf(warnings);excluded=Set.copyOf(excluded);}
  public String describe(){StringJoiner out=new StringJoiner("\n");out.add(questionId+" | WIKIDATA_"+state+" | "+subject+" -> "+target);
   out.add("Requête structurée : "+new Request(subject,target).json());
   if(!candidates.isEmpty())out.add("Préciser l'identifiant Q : "+candidates);
   for(var d:documents){out.add("Document "+d.id()+" « "+d.label()+" » | "+d.url()+" | révision "+d.revision()+" | consulté "+d.retrievedAt()+" | cache="+d.cached());for(var c:d.claims())out.add("Déclaration "+c.id()+" : "+c.subject()+" "+c.relation()+" "+c.object()+" ; rang="+c.rank()+" ; qualificatifs="+c.qualifiersJson()+" ; références="+c.referencesJson());}
   if(reasoning!=null){out.add("Inférence Jena : "+reasoning.status()+" ; sources utilisées "+reasoning.supportClaimIds());for(var p:reasoning.certificates())out.add("Certificat contrôlé : "+p);}
   if(!excluded.isEmpty())out.add("Déclarations exclues : "+excluded);
   warnings.forEach(w->out.add("Diagnostic : "+w));out.add("Données Wikidata non authentifiées : résultat conditionnel, aucune vérité permanente ajoutée. Absence de chemin ≠ faux.");return out.toString();}
 }
 private final WikidataSource source;private final IntegratedQuestionCycle cycle;private final Map<String,Answer> sessions=new LinkedHashMap<>();private final Map<String,String> acquisition=new HashMap<>();
 public WikidataClassificationService(IntegratedQuestionCycle cycle,WikidataSource source){this.cycle=Objects.requireNonNull(cycle);this.source=Objects.requireNonNull(source);}
 public Answer classify(String subject,String target){
  var request=Request.read(new Request(subject,target).json());subject=request.subject();target=request.target();
  if(sessions.size()>=64)throw new IllegalArgumentException("Budget de 64 classifications atteint");String id=cycle.openOntologyQuestion("Classification documentaire : "+subject+" -> "+target);List<String> warnings=new ArrayList<>();List<Snapshot> docs=new ArrayList<>();
  String s,t;
  try{
   var left=resolve(subject);if(left.size()!=1||!subject.matches("Q[1-9][0-9]{0,11}"))return remember(new Answer(id,"NEEDS_CLARIFICATION",subject,target,left,docs,null,warnings,Set.of()));
   var right=resolve(target);if(right.size()!=1||!target.matches("Q[1-9][0-9]{0,11}"))return remember(new Answer(id,"NEEDS_CLARIFICATION",subject,target,right,docs,null,warnings,Set.of()));
   s=left.getFirst().id();t=right.getFirst().id();
  }catch(RuntimeException ex){throw ex;}catch(Exception ex){if(ex instanceof InterruptedException)Thread.currentThread().interrupt();return remember(new Answer(id,"WEB_FAILED",subject,target,List.of(),docs,null,List.of(ex.getClass().getSimpleName()),Set.of()));}
  record Pending(String id,int depth){};var queue=new ArrayDeque<Pending>();queue.add(new Pending(s,0));Set<String> seen=new HashSet<>();Set<String> signature=new HashSet<>(List.of(s,t));int edges=0,attempts=0;boolean limited=false,failed=false;
  while(!queue.isEmpty()){
   var next=queue.removeFirst();if(!seen.add(next.id())||next.id().equals(t))continue;
   if(next.depth()>4){limited=true;continue;}if(attempts++>=12){limited=true;break;}
   Snapshot d;try{d=source.load(next.id());if(!d.id().equals(next.id()))throw new java.io.IOException("Identifiant de document incohérent");}catch(RuntimeException ex){throw ex;}catch(Exception ex){if(ex instanceof InterruptedException)Thread.currentThread().interrupt();warnings.add(next.id()+" : "+ex.getClass().getSimpleName());failed=true;continue;}
   Set<String> proposed=new HashSet<>(signature);d.usable().forEach(c->{proposed.add(c.subject());proposed.add(c.object());});
   if(edges+d.usable().size()>128||proposed.size()>128){limited=true;continue;}signature=proposed;docs.add(d);edges+=d.usable().size();warnings.addAll(d.warnings());
   // Après la première arête, seules les sous-classes sont utiles aux deux schémas autorisés.
   for(var c:d.usable())if(next.depth()==0||c.relation()==Relation.SUBCLASS_OF)queue.addLast(new Pending(c.object(),next.depth()+1));
  }
  if(limited)warnings.add("Exploration partielle : 12 documents, profondeur 4, 128 arêtes maximum");
  var initial=new Answer(id,failed?"WEB_FAILED":limited?"RESOURCE_LIMIT":"UNKNOWN",s,t,List.of(),docs,null,warnings,Set.of());acquisition.put(id,initial.state());return remember(assess(initial));
 }
 private List<Candidate> resolve(String text)throws Exception{if(text.matches("Q[1-9][0-9]{0,11}"))return List.of(new Candidate(text,text,"identifiant fourni"));var result=List.copyOf(source.search(text));if(result.size()>5)throw new IllegalArgumentException("Trop de candidats");return result;}
 public Answer revisit(String id){return remember(assess(session(id)));}
 public Answer exclude(String id,String claimId){var a=session(id);if(a.documents().stream().flatMap(d->d.claims().stream()).noneMatch(c->c.id().equals(claimId)))throw new IllegalArgumentException("Déclaration absente de cette question");Set<String> excluded=new HashSet<>(a.excluded());excluded.add(claimId);return remember(assess(new Answer(a.questionId(),"UNKNOWN",a.subject(),a.target(),a.candidates(),a.documents(),null,a.warnings(),excluded)));}
 private Answer assess(Answer a){if(!a.candidates().isEmpty()||!a.subject().matches("Q[1-9][0-9]{0,11}")||!a.target().matches("Q[1-9][0-9]{0,11}"))return a;
  var claims=a.documents().stream().flatMap(d->d.usable().stream()).filter(c->!a.excluded().contains(c.id())).toList();var result=new JenaClassificationEngine().evaluate(a.subject(),a.target(),claims);
  String acquired=acquisition.getOrDefault(a.questionId(),a.state());String state=result.status().equals("SUPPORTED_CONDITIONALLY")?result.status():acquired.equals("WEB_FAILED")||acquired.equals("RESOURCE_LIMIT")?acquired:result.status();return new Answer(a.questionId(),state,a.subject(),a.target(),a.candidates(),a.documents(),result,a.warnings(),a.excluded());
 }
 private Answer session(String id){var a=sessions.get(id);if(a==null)throw new IllegalArgumentException("Classification inconnue");return a;}
 private Answer remember(Answer a){sessions.put(a.questionId(),a);cycle.recordStructuredResearch(a.questionId(),"Wikidata : "+a.state()+", hypothèses locales");cycle.recordStructuredFinding(a.questionId(),"WIKIDATA_"+a.state());return a;}
}
