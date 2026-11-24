package fr.ivorama.cognitive;
import java.util.*;
import org.apache.jena.graph.*;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.rulesys.*;
import static fr.ivorama.cognitive.WikidataSource.*;

/** Jena calcule ; le certificat relu vérifie seulement deux schémas autorisés. */
public final class JenaClassificationEngine {
 public record Fact(String subject,Relation relation,String object){public Fact{subject=WikidataSource.qid(subject);object=WikidataSource.qid(object);Objects.requireNonNull(relation);}}
 public record Certificate(String rule,Fact conclusion,List<Certificate> premises){public Certificate{premises=List.copyOf(premises);}}
 public record Result(String status,List<Certificate> certificates,List<String> supportClaimIds){public Result{certificates=List.copyOf(certificates);supportClaimIds=List.copyOf(supportClaimIds);}}
 private static final String ITEM="http://www.wikidata.org/entity/", PROP="http://www.wikidata.org/prop/direct/";
 private static final List<Rule> RULES=Rule.parseRules("[sub: (?a <"+PROP+"P279> ?b) (?b <"+PROP+"P279> ?c) -> (?a <"+PROP+"P279> ?c)]\n[type: (?x <"+PROP+"P31> ?a) (?a <"+PROP+"P279> ?b) -> (?x <"+PROP+"P31> ?b)]");
 public Result evaluate(String subject,String target,List<Claim> claims){
  WikidataSource.qid(subject);WikidataSource.qid(target);if(claims.size()>128)throw new IllegalArgumentException("Budget de 128 relations dépassé");
  Set<String> entities=new HashSet<>();claims.forEach(c->{entities.add(c.subject());entities.add(c.object());});if(entities.size()>128)throw new IllegalArgumentException("Budget de 128 identifiants dépassé");
  Map<Fact,List<String>> bases=new LinkedHashMap<>();for(var c:claims){if(!c.unqualified()||c.rank().equals("deprecated"))throw new IllegalArgumentException("Prémisse documentaire non sélectionnée");bases.computeIfAbsent(new Fact(c.subject(),c.relation(),c.object()),k->new ArrayList<>()).add(c.id());}
  Model raw=ModelFactory.createDefaultModel();InfModel inf=null;
  try{
   bases.keySet().forEach(f->raw.getGraph().add(triple(f)));var reasoner=new GenericRuleReasoner(RULES);reasoner.setMode(GenericRuleReasoner.FORWARD_RETE);reasoner.setDerivationLogging(true);inf=ModelFactory.createInfModel(reasoner,raw);inf.prepare();
   List<Certificate> proofs=new ArrayList<>();boolean missingCertificate=false;
   for(var relation:Relation.values()){
    var goal=new Fact(subject,relation,target);var query=new ParameterizedSparqlString("ASK { ?s ?p ?o }");query.setIri("s",ITEM+subject);query.setIri("p",PROP+relation.property);query.setIri("o",ITEM+target);
    try(var execution=QueryExecutionFactory.create(query.asQuery(),inf)){if(!execution.execAsk())continue;}
    var certificate=extract(goal,inf,bases.keySet(),new HashSet<>(),new int[]{0});
    if(certificate.isPresent()&&verify(certificate.get(),bases.keySet()))proofs.add(certificate.get());else missingCertificate=true;
   }
   Set<String> supports=new TreeSet<>();for(var proof:proofs)leaves(proof,bases,supports);
   return new Result(proofs.isEmpty()?(missingCertificate?"CERTIFICATE_LIMIT":"UNKNOWN"):"SUPPORTED_CONDITIONALLY",proofs,List.copyOf(supports));
  }finally{if(inf!=null)inf.close();else raw.close();}
 }
 private static Optional<Certificate> extract(Fact goal,InfModel inf,Set<Fact> base,Set<Fact> path,int[] examined){
  if(++examined[0]>1024||path.size()>=32||path.contains(goal))return Optional.empty();
  if(base.contains(goal))return Optional.of(new Certificate("SOURCE",goal,List.of()));
  var next=new HashSet<>(path);next.add(goal);var it=inf.getDerivation(inf.asStatement(triple(goal)));
  while(it!=null&&it.hasNext()){
   if(++examined[0]>1024)break;var derivation=it.next();if(!(derivation instanceof RuleDerivation d)||!d.getConclusion().equals(triple(goal)))continue;
   List<Certificate> premises=new ArrayList<>();for(var matched:d.getMatches()){var sub=extract(fact(matched),inf,base,next,examined);if(sub.isEmpty())break;premises.add(sub.get());}
   if(premises.size()==2){var result=new Certificate(d.getRule().getName(),goal,premises);if(verify(result,base))return Optional.of(result);}
  }
  return Optional.empty();
 }
 /** Contrôle indépendant de l'exécution Jena : pas d'héritage de propriété arbitraire. */
 public static boolean verify(Certificate certificate,Set<Fact> base){return verify(certificate,base,0,new int[]{0});}
 private static boolean verify(Certificate c,Set<Fact> base,int depth,int[] count){
  if(c==null||depth>32||++count[0]>1024)return false;
  if(c.rule().equals("SOURCE"))return c.premises().isEmpty()&&base.contains(c.conclusion());
  if(c.premises().size()!=2||c.premises().stream().anyMatch(p->!verify(p,base,depth+1,count)))return false;
  Fact a=c.premises().get(0).conclusion(),b=c.premises().get(1).conclusion(),g=c.conclusion();
  if(!a.object().equals(b.subject())||!a.subject().equals(g.subject())||!b.object().equals(g.object())||b.relation()!=Relation.SUBCLASS_OF)return false;
  return c.rule().equals("sub")&&a.relation()==Relation.SUBCLASS_OF&&g.relation()==Relation.SUBCLASS_OF||c.rule().equals("type")&&a.relation()==Relation.INSTANCE_OF&&g.relation()==Relation.INSTANCE_OF;
 }
 private static void leaves(Certificate c,Map<Fact,List<String>> base,Set<String> result){if(c.rule().equals("SOURCE"))result.add(base.get(c.conclusion()).getFirst());else c.premises().forEach(p->leaves(p,base,result));}
 private static Triple triple(Fact f){return Triple.create(NodeFactory.createURI(ITEM+f.subject()),NodeFactory.createURI(PROP+f.relation().property),NodeFactory.createURI(ITEM+f.object()));}
 private static Fact fact(Triple t){if(!t.getSubject().isURI()||!t.getObject().isURI()||!t.getSubject().getURI().startsWith(ITEM)||!t.getObject().getURI().startsWith(ITEM))throw new IllegalArgumentException("Triplet hors signature");Relation relation=Arrays.stream(Relation.values()).filter(r->t.getPredicate().equals(NodeFactory.createURI(PROP+r.property))).findFirst().orElseThrow();return new Fact(t.getSubject().getURI().substring(ITEM.length()),relation,t.getObject().getURI().substring(ITEM.length()));}
}
