package com.ivorama.nlp.transform;
import java.util.*;
/** Source sense is resolved first; direct Robert edges are the only Robert candidates. */
public final class RobertDisambiguator {
 public record Decision(String status,String sense,double score,List<String> candidates){}
 public record SenseScore(String sense,double direct,double neighborhood,double total){}
 public record CandidateContextScore(double value,String sense,double direct,double neighborhood,boolean evaluated){}
 /** Individual candidate fit on the same source-excluded context. Does not authorize synonymy. */
 public CandidateContextScore candidateContextScore(String lemma,String pos,String candidate,Collection<String> terms,String selectedSourceSense){
  var ctx=context(lemma,terms);var senses=evidence.sourceSenses(candidate,pos);
  if(ctx.isEmpty()||senses.isEmpty())return new CandidateContextScore(0,"",0,0,false);
  // If the identified source sense is attested for this candidate, evaluate that reading only.
  if(!selectedSourceSense.isBlank()&&senses.contains(selectedSourceSense))senses=Set.of(selectedSourceSense);
  CandidateContextScore best=null;
  for(String sense:senses){double direct=ctx.stream().mapToDouble(w->evidence.direct(sense,w)).average().orElse(0);
   double neighbor=ctx.stream().mapToDouble(w->evidence.neighborhood(sense,w)).average().orElse(0);
   double value=(settings.contextWeight()*direct+settings.neighborhoodWeight()*neighbor)/(settings.contextWeight()+settings.neighborhoodWeight());
   if(best==null||value>best.value())best=new CandidateContextScore(value,sense,direct,neighbor,true);
  }return best;
 }
 private final RobertGroupEvidence evidence;private final RobertCandidateIndex index;private final RobertSettings settings;
 private record Key(String lemma,String pos,List<String> context,List<String> focus){}
 private final Map<Key,Decision> decisions=new HashMap<>();
 private final Map<Key,List<SenseScore>> scoreCache=new HashMap<>();
 public RobertDisambiguator(RobertGroupEvidence evidence,RobertCandidateIndex index){this(evidence,index,RobertSettings.defaults());}
 public RobertDisambiguator(RobertGroupEvidence evidence,RobertCandidateIndex index,RobertSettings settings){this.evidence=evidence;this.index=index;this.settings=settings;}
 private List<String> context(String lemma,Collection<String> terms){var out=new TreeSet<String>();for(String t:terms)if(!t.isBlank()&&!RobertGroupEvidence.norm(t).equals(RobertGroupEvidence.norm(lemma)))out.add(RobertGroupEvidence.norm(t));return List.copyOf(out);}
 public List<SenseScore> scores(String lemma, String pos, Collection<String> terms) {
  return scores(lemma, pos, terms, List.of());
 }
 private static double contextualAverage(List<String> terms, List<String> focus,
                                        java.util.function.ToDoubleFunction<String> support) {
  double evidence = terms.stream().mapToDouble(support).sum();
  return (evidence + 2 * focus.stream().mapToDouble(support).sum()) / (terms.size() + 2 * focus.size());
 }
 synchronized List<SenseScore> scores(String lemma,String pos,Collection<String> terms,List<String> focus){var context=context(lemma,terms);var key=new Key(lemma,pos,context,focus);var cached=scoreCache.get(key);if(cached!=null)return cached;var out=new ArrayList<SenseScore>();if(context.isEmpty())return List.of();
  for(String sense:evidence.sourceSenses(lemma,pos)){
   double direct=contextualAverage(context, focus, w -> evidence.direct(sense,w));
   double neighbors = contextualAverage(context, focus, word -> {
    double ordinary = evidence.neighborhood(sense, word);
    return pos.equals("n") ? Math.max(ordinary, evidence.derivationalSupport(sense, word)) : ordinary;
   });
   // Source-specific Robert groups are expanded through anchored lexical senses.
   // This signature is built before any candidate is selected; no transitive substitution edge is added.
   var expansion=new TreeSet<String>();var sourceNeighbors=evidence.neighbors(sense);
   for(var m:index.lookup(lemma))if(m.relation().pos().equals(pos))for(var r:index.group(m.relation()))
    if(evidence.forbiddenRelation(sense,r.term(),pos).isEmpty())for(String memberSense:evidence.senses(r.term(),pos))
     if(memberSense.equals(sense)||sourceNeighbors.contains(memberSense)||evidence.glossOverlap(sense,memberSense)>0)expansion.addAll(evidence.neighbors(memberSense));
   double group=contextualAverage(context, focus, c->expansion.stream().mapToDouble(s->evidence.direct(s,c)/3).max().orElse(0));
   neighbors=Math.max(neighbors,group);
   double total=(settings.contextWeight()*direct+settings.neighborhoodWeight()*neighbors)/(settings.contextWeight()+settings.neighborhoodWeight());
   out.add(new SenseScore(sense,direct,neighbors,total));
  }out.sort(Comparator.comparingDouble(SenseScore::total).reversed().thenComparing(SenseScore::sense));var result=List.copyOf(out);if(scoreCache.size()>4096)scoreCache.clear();scoreCache.put(key,result);return result;
 }
 public Decision select(String lemma,String pos,Collection<String> terms){
  return select(lemma, pos, terms, List.of());
 }
 private Decision select(String lemma, String pos, Collection<String> terms, List<String> focus) {
  var key=new Key(lemma,pos,context(lemma,terms),focus);synchronized(decisions){var cached=decisions.get(key);if(cached!=null)return cached;var result=selectUncached(lemma,pos,key.context(),focus);if(decisions.size()>4096)decisions.clear();decisions.put(key,result);return result;}
 }
 private Decision selectUncached(String lemma,String pos,Collection<String> terms,List<String> focus){
  var context=context(lemma,terms);if(context.isEmpty())return new Decision("NO_CONTEXT","",0,List.of());
  var ranks=scores(lemma,pos,context,focus);if(ranks.isEmpty())return new Decision("SOURCE_SENSE_UNAVAILABLE","",0,List.of());
  // Resolve among attested source readings before ranking: an unreviewed automatic translation must not win over validated readings.
  var reviewed=ranks.stream().filter(r->evidence.reviewed(lemma,r.sense())).toList();
  if(!reviewed.isEmpty())ranks=reviewed;
  var top=ranks.get(0);double second=ranks.size()>1?ranks.get(1).total():0;
  if(top.total()<=0||top.total()<settings.minContext()||ranks.size()>1&&(top.total()-second<=0||top.total()-second<settings.minMargin()))return new Decision("AMBIGUOUS_OR_UNSUPPORTED","",top.total(),List.of());
  if(settings.requireReviewedSource()&&!evidence.reviewed(lemma,top.sense()))return new Decision("SOURCE_SENSE_NOT_REVIEWED","",top.total(),List.of());
  if(ranks.stream().anyMatch(s->evidence.reviewed(lemma,s.sense()))&&!evidence.reviewed(lemma,top.sense()))return new Decision("UNREVIEWED_SENSE_CONFLICT","",top.total(),List.of());
  double anchor=groupAnchor(lemma,pos,top.sense());
  double strongest=ranks.stream().mapToDouble(s->groupAnchor(lemma,pos,s.sense())).max().orElse(0);
  if(strongest>anchor)return new Decision("ROBERT_GROUP_SOURCE_CONFLICT","",top.total(),List.of());
  var candidates=new TreeSet<String>();for(var match:index.lookup(lemma))if(match.relation().pos().equals(pos)&&candidate(lemma,pos,match.candidate(),context,top).allowed())candidates.add(RobertGroupEvidence.norm(match.candidate()));
  if(candidates.isEmpty())return new Decision("NO_COMPATIBLE_CANDIDATE",top.sense(),top.total(),List.of());
  int strongestProof=candidates.stream().mapToInt(c->proofTier(lemma,pos,c,top.sense())).max().orElse(0);
  var strongestCandidates=candidates.stream().filter(c->proofTier(lemma,pos,c,top.sense())==strongestProof).toList();
  if(strongestCandidates.size()>1)return new Decision("AMBIGUOUS_CANDIDATES",top.sense(),top.total(),List.of());
  if(context.contains(strongestCandidates.get(0)))return new Decision("CANDIDATE_ALREADY_IN_CONTEXT",top.sense(),top.total(),List.of());
  return new Decision("CANDIDATES",top.sense(),top.total(),strongestCandidates);
 }
 public CandidateDecision assess(String lemma,String pos,String candidate,Collection<String> terms){
  return assess(lemma, pos, candidate, terms, List.of());
 }
 CandidateDecision assess(String lemma, String pos, String candidate, Collection<String> terms, Collection<String> preferred) {
  var context = context(lemma, terms);
  var focus = context(lemma, preferred).stream().filter(context::contains).toList();
  var decision=assessSemantic(lemma,pos,candidate,context,focus);
  var individual=candidateContextScore(lemma,pos,candidate,terms,decision.sense());
  return new CandidateDecision(decision.allowed(),decision.reason(),individual.value(),decision.sense(),decision.evidence()+"; source_score="+decision.score()+"; candidate_score_evaluated="+individual.evaluated()+"; candidate_sense="+individual.sense()+"; candidate_C="+individual.direct()+"; candidate_N="+individual.neighborhood()+"; candidate_context_score="+individual.value()+"; candidate_fit_is_not_synonymy_authorization");
 }
 private CandidateDecision assessSemantic(String lemma,String pos,String candidate,Collection<String> terms,List<String> focus){
  var selected=select(lemma,pos,terms,focus);if(selected.sense().isEmpty())return new CandidateDecision(false,selected.status(),selected.score(),"","Source-first; no positive sense decision");
  if(!selected.candidates().contains(RobertGroupEvidence.norm(candidate)))return new CandidateDecision(false,selected.status().equals("CANDIDATES")?"STRONGER_LEXICAL_RELATION_AVAILABLE":selected.status(),selected.score(),selected.sense(),"No alphabetical or forced replacement in a lexical tie");
  var context=context(lemma,terms);var score=scores(lemma,pos,context,focus).stream().filter(s->s.sense().equals(selected.sense())).findFirst().orElseThrow();
  return candidate(lemma,pos,candidate,context,score);
 }
 private CandidateDecision candidate(String lemma,String pos,String candidate,List<String> context,SenseScore source){
  String c=RobertGroupEvidence.norm(candidate),original=RobertGroupEvidence.norm(lemma);
  var matches=index.lookup(original).stream().filter(m->m.relation().pos().equals(pos)&&RobertGroupEvidence.norm(m.candidate()).equals(c)).toList();
  if(matches.isEmpty())return CandidateDecision.reject("NOT_A_DIRECT_ROBERT_RELATION");
  // Inverse lookup is useful for discovery, but its source entry does not attest
  // the target register or the Robert sense of the word currently being rewritten.
  if(matches.stream().noneMatch(m->!m.inverse()))return CandidateDecision.reject("NO_FORWARD_ROBERT_SENSE_EVIDENCE");
  if(c.equals(original))return CandidateDecision.reject("IDENTICAL_SOURCE");
  // A candidate may have a hypernym reading as well as the identified synonymous reading.
  // Apply the typed veto to the proposed sense, not to every homonymous reading.
  boolean exact=evidence.sourceSenses(c,pos).contains(source.sense());
  String forbidden=evidence.forbiddenRelation(source.sense(),c,pos,exact);
  if(!forbidden.isEmpty())return new CandidateDecision(false,"NON_SYNONYM_RELATION:"+forbidden,source.total(),source.sense(),"Typed WOLF edge; no proximity override");
  boolean reciprocal=index.lookup(c).stream().anyMatch(m->!m.inverse()&&m.relation().pos().equals(pos)&&RobertGroupEvidence.norm(m.candidate()).equals(original));
  boolean direct=matches.stream().anyMatch(m->!m.inverse());
  boolean reverse=matches.stream().anyMatch(RobertCandidateIndex.Match::inverse);
  int shared=0;double overlap=0;
  for(String sense:evidence.senses(c,pos)){
   int hits=0;for(String word:context)if(evidence.support(source.sense(),word)>0&&evidence.support(sense,word)>0)hits++;
   shared=Math.max(shared,hits);overlap=Math.max(overlap,evidence.glossOverlap(source.sense(),sense));
  }
  // A reverse index is provenance, not reciprocity. Require actual forward entries both ways.
  boolean relationProof=exact||(reciprocal&&direct&&reverse)||(direct&&matches.stream().anyMatch(m->m.relation().corroborated())&&shared>=2&&overlap>0);
  if(!relationProof)return new CandidateDecision(false,"CROSS_SENSE_SYNONYMY_UNPROVEN",source.total(),source.sense(),"Robert edge exists; context/proximity alone cannot authorize");
  String groups=matches.stream().map(m->m.relation().page()+":"+m.relation().head()+":"+m.relation().sense()).distinct().sorted().toList().toString();
  return new CandidateDecision(true,"ROBERT_SENSE_SUPPORTED",source.total(),source.sense(),"C="+source.direct()+"; N="+source.neighborhood()+"; weights="+settings.contextWeight()+","+settings.neighborhoodWeight()+"; manually_reviewed_source="+evidence.reviewed(lemma,source.sense())+"; same_synset="+exact+"; reciprocal="+reciprocal+"; shared_context="+shared+"; groups="+groups+"; not_probability");
 }
 public RobertCandidateIndex index(){return index;}
 private int proofTier(String lemma,String pos,String candidate,String sense){
  // An identified synonymous reading is stronger than reciprocity without sense alignment.
  // Tiers order evidence; they are not arithmetic weights or probability estimates.
  if(evidence.sourceSenses(candidate,pos).contains(sense))return evidence.reviewed(candidate,sense)?4:3;
  boolean direct=index.lookup(lemma).stream().anyMatch(m->!m.inverse()&&m.relation().pos().equals(pos)&&RobertGroupEvidence.norm(m.candidate()).equals(candidate));
  boolean reverse=index.lookup(candidate).stream().anyMatch(m->!m.inverse()&&m.relation().pos().equals(pos)&&RobertGroupEvidence.norm(m.candidate()).equals(RobertGroupEvidence.norm(lemma)));
  return direct&&reverse?2:1;
 }
 private double groupAnchor(String lemma,String pos,String sense){
  var groups=new LinkedHashMap<String,Set<String>>();
  for(var m:index.lookup(lemma))if(!m.inverse()&&m.relation().pos().equals(pos)){
   var r=m.relation();groups.computeIfAbsent(r.page()+":"+r.head()+":"+r.sense(),k->new HashSet<>()).add(RobertGroupEvidence.norm(r.term()));
  }
  double best=0;for(var words:groups.values()){var known=words.stream().filter(w->!evidence.senses(w,pos).isEmpty()).toList();if(!known.isEmpty())best=Math.max(best,(double)known.stream().filter(w->evidence.senses(w,pos).contains(sense)).count()/known.size());}return best;
 }
 public CandidateDecision assessOmw(String lemma,String pos,String candidate,Collection<String> terms){var decision=select(lemma,pos,terms);if(decision.sense().isBlank())return new CandidateDecision(false,decision.status(),decision.score(),"","No source sense");String forbidden=evidence.forbiddenRelation(decision.sense(),candidate,pos);if(!forbidden.isBlank())return new CandidateDecision(false,"NON_SYNONYM_RELATION:"+forbidden,decision.score(),decision.sense(),"Typed WOLF edge");return evidence.senses(candidate,pos).contains(decision.sense())?new CandidateDecision(true,"DIRECT_OMW_SOURCE_SENSE",decision.score(),decision.sense(),"Source-first lexical relation; grammar mandatory"):CandidateDecision.reject("NOT_IN_IDENTIFIED_SOURCE_SENSE");}
 public RobertGroupEvidence evidence(){return evidence;}
 public boolean fallbackEnabled(){return settings.fallbackToOmw();}
}
