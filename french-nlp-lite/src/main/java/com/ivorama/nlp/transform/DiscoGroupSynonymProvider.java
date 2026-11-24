package com.ivorama.nlp.transform;
import com.ivorama.nlp.core.*;import java.util.*;
/** Strict mode uses Robert; creative mode also explores attested DISCO neighbors. */
public final class DiscoGroupSynonymProvider implements SynonymProvider{
 public record GroupScore(String group,List<String> candidates,double fit){}
 public record Detail(String candidate,String group,double groupFit,double lexicalFit,double contextFit,double retention,double score,String reason){}
 private record Group(String id,Set<String> members,Set<String> candidates){}
 private record AnalysisKey(int index, String original, String tag, int head, String dependency) {}
 private record Analysis(List<GroupScore> groups,List<Detail> details,String sourceSense,String selected){}
 private static final Map<java.nio.file.Path,DiscoWordSpace> MODELS=new java.util.concurrent.ConcurrentHashMap<>();
 private static final class IndexHolder { static final RobertCandidateIndex INDEX=loadIndex(); }
 private static RobertCandidateIndex loadIndex(){try{return RobertCandidateIndex.load();}catch(java.io.IOException e){throw new IllegalStateException(e);}}
 public String metrics(){return disco.metrics();}
 private final RobertCandidateIndex index;private final RobertGroupEvidence evidence;private final LexiconMorphologicalEngine morph=new LexiconMorphologicalEngine();private final DiscoSettings settings;private final DiscoWordSpace disco;
 private List<SentenceAnalysis> document=List.of();
 private final BoundedCache<TokenData,String> lemmas=new BoundedCache<>(4096);
 private String lemma(TokenData t){return Boolean.getBoolean("ivorama.cache.disabled")?computeLemma(t):lemmas.get(t,this::computeLemma);}

 private final Map<SentenceAnalysis,Map<AnalysisKey,Analysis>> memo=Collections.synchronizedMap(new WeakHashMap<>());
 private final Map<String,Map<String,Float>> prototypes=new LinkedHashMap<>(128,.75f,true){protected boolean removeEldestEntry(Map.Entry<String,Map<String,Float>> e){return size()>128;}};
 private Map<String,Float> prototype(String key,Collection<String> terms){return Boolean.getBoolean("ivorama.cache.disabled")?disco.prototype(terms):prototypes.computeIfAbsent(key,k->disco.prototype(terms));}
 public DiscoGroupSynonymProvider(DiscoSettings settings){this.settings=settings;this.disco=MODELS.computeIfAbsent(settings.directory().toAbsolutePath().normalize(),DiscoWordSpace::new);index=IndexHolder.INDEX;evidence=RobertGroupEvidence.load();}
 @Override public synchronized void prepareDocument(List<SentenceAnalysis> analyses) {
  if (document == analyses) return;
  document = List.copyOf(analyses);
  memo.clear();
 }
 private String computeLemma(TokenData t){String l=morph.analyze(t).lemma();if(!l.isEmpty())return l;String alias=t.word().replace("oe","œ").replace("ae","æ");if(!alias.equals(t.word())){l=morph.analyze(new TokenData(t.index(),alias,t.pos())).lemma();if(!l.isEmpty())return l;}return LexiconMorphologicalEngine.norm(t.word());}
 private String pos(TokenData t){return Set.of("PROPN","NPP").contains(t.pos())?"":t.isNoun()?"n":t.isAdjective()?"a":"";}
 private boolean content(TokenData t){return !Set.of("PROPN","NPP").contains(t.pos())&&(t.isNoun()||t.isAdjective()||t.pos().startsWith("V")||t.pos().equals("ADV"));}
 private Map<String,Double> context(TokenData target,SentenceAnalysis a){var weights=new TreeMap<String,Double>();String original=lemma(target);for(var t:a.tokens())if(t.index()!=target.index()&&content(t)){
  double w=1d/(1+Math.abs(t.index()-target.index()));if(t.index()==target.headIndex()||t.headIndex()==target.index())w*=4;
  String l=lemma(t);if(!l.equals(original))weights.merge(l,w,Double::sum);
 }return weights;}
 private Map<String,Double> paragraphContext(String original,SentenceAnalysis a){var out=new TreeMap<String,Double>();for(var sentence:document)if(sentence!=a)for(var t:sentence.tokens())if(content(t)){String l=lemma(t);if(!l.equals(original))out.merge(l,.1,Double::sum);}return out;}
 private List<Group> groups(String lemma,String pos){var groups=new TreeMap<String,Group>();for(var m:index.lookup(lemma))if(m.relation().pos().equals(pos)){var r=m.relation();String id=r.page()+":"+r.line()+":"+r.head()+":"+r.sense();var g=groups.computeIfAbsent(id,k->new Group(k,new TreeSet<>(),new TreeSet<>()));g.members().add(r.head());for(var member:index.group(r))g.members().add(member.term());g.members().remove(lemma);g.candidates().add(m.candidate());}var merged=new TreeMap<String,Group>();for(var g:groups.values()){String signature=String.join("|",g.members());var old=merged.get(signature);if(old==null)merged.put(signature,g);else{var candidates=new TreeSet<>(old.candidates());candidates.addAll(g.candidates());merged.put(signature,new Group(old.id()+"+"+g.id(),old.members(),candidates));}}return List.copyOf(merged.values());}
 public List<String> inventory(TokenData t,SynonymRegister register,SentenceAnalysis a){
  if(a==null||pos(t).isEmpty()||settings.editsDisabled(pos(t))||LexicalSafety.isProtectedExpression(a,t)||GrammaticalScope.quoted(a,t)||GrammaticalScope.operator(t))return List.of();
  return analyze(t,a).details().stream().map(Detail::candidate).distinct().toList();
 }
 private String sourceSense(String original,String pos,DiscoWordSpace.ContextVector ctx){var ranked=new ArrayList<Map.Entry<String,Double>>();var senses=evidence.sourceSenses(original,pos);var reviewed=senses.stream().filter(s->evidence.reviewed(original,s)).toList();if(!reviewed.isEmpty())senses=new TreeSet<>(reviewed);for(String s:senses){var terms=new TreeSet<>(evidence.members(s));for(String n:evidence.neighbors(s))terms.addAll(evidence.members(n));terms.remove(original);double fit=disco.cosine(prototype("sense:"+original+":"+s,terms),ctx);ranked.add(Map.entry(s,fit));}ranked.sort(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));if(ranked.isEmpty()||ranked.get(0).getValue()<=0)return "";if(ranked.size()>1&&(ranked.get(0).getValue()-ranked.get(1).getValue())/ranked.get(0).getValue()<settings.minMargin())return "";return ranked.get(0).getKey();}
 private synchronized Analysis analyze(TokenData token,SentenceAnalysis a){String original=lemma(token),pos=pos(token);
  var cache=memo.computeIfAbsent(a,k->new HashMap<>());
  var key=new AnalysisKey(token.index(),original,token.pos(),token.headIndex(),token.dependency());
  var prior=cache.get(key);
  if(prior!=null&&!Boolean.getBoolean("ivorama.cache.disabled"))return prior;var weights=context(token,a);var ctx=disco.contextVector(weights);double originalFit=disco.cosine(disco.vector(original),ctx);var sourceCtx=new TreeMap<>(weights);var paragraph=paragraphContext(original,a);paragraph.forEach((k,w)->sourceCtx.merge(k,Math.min(w,.05),Double::sum));String source=sourceSense(original,pos,disco.contextVector(sourceCtx));var ranked=new ArrayList<GroupScore>();var all=groups(original,pos);
  for(var group:all)ranked.add(new GroupScore(group.id(),List.copyOf(group.candidates()),disco.cosine(prototype("group:"+original+":"+group.id(),group.members()),ctx)));
  ranked.sort(Comparator.comparingDouble(GroupScore::fit).reversed().thenComparing(GroupScore::group));boolean groupClear=!ranked.isEmpty()&&ranked.get(0).fit()>=settings.minGroupScore()&&(ranked.size()==1||(ranked.get(0).fit()-ranked.get(1).fit())/Math.max(1e-12,ranked.get(0).fit())>=settings.effectiveMargin(pos));String bestGroup=ranked.isEmpty()?"":ranked.get(0).group();var details=new ArrayList<Detail>();
  for(var group:all){double gfit=ranked.stream().filter(g->g.group().equals(group.id())).findFirst().orElseThrow().fit();boolean anchored=source.isEmpty()||group.members().stream().anyMatch(w->evidence.sourceSenses(w,pos).contains(source));for(String candidate:group.candidates()){
   double fit=disco.cosine(disco.vector(candidate),ctx),lex=disco.wordSimilarity(original,candidate),ret=originalFit>0?Math.min(1,fit/originalFit):0,score=Math.cbrt(gfit*fit*lex);String reason="ELIGIBLE";
   if(disco.vector(candidate).isEmpty()||ctx.isEmpty()||disco.vector(original).isEmpty())reason="DISTRIBUTIONAL_DATA_MISSING";
   else if(!groupClear&&settings.semanticFreedom(pos)==0)reason="ROBERT_GROUP_AMBIGUOUS";
   else if(!group.id().equals(bestGroup)&&!CreativeSelection.groupAllowed(gfit,ranked.get(0).fit(),settings.freedom(pos)))reason="OTHER_ROBERT_GROUP";
   else if(settings.semanticFreedom(pos)==0&&!evidence.sourceSenses(original,pos).isEmpty()&&source.isEmpty())reason="SOURCE_SENSE_AMBIGUOUS";
   else if(!source.isEmpty()&&forbidden(source,candidate,pos))reason="FORBIDDEN_LEXICAL_RELATION";
   else if(!source.isEmpty()&&!anchored)reason="ROBERT_GROUP_SOURCE_SENSE_CONFLICT";
   else if(settings.semanticFreedom(pos)==0&&!source.isEmpty()&&!evidence.sourceSenses(candidate,pos).contains(source)&&!crossSenseProof(original,candidate,pos,source))reason="CANDIDATE_SENSE_RELATION_UNPROVEN";
   else if(pos.equals("n")&&evidence.attestedAnimate(original)&&!evidence.possibleAnimate(candidate))reason="ANIMATE_CATEGORY_CONFLICT";
   else if(ret<settings.effectiveRetention(pos))reason="CONTEXT_DEGRADATION";
   else if(fit<=0||lex<=0)reason="NO_DISTRIBUTIONAL_SUPPORT";
   else if(candidate.equals(original)||weights.containsKey(candidate))reason="CANDIDATE_ALREADY_IN_CONTEXT";
   // With freedom, unproven identity becomes a graded preference, not a veto.
   if(settings.semanticFreedom(pos)>0&&!source.isEmpty()&&(!anchored||!evidence.sourceSenses(candidate,pos).contains(source)&&!crossSenseProof(original,candidate,pos,source)))score*=settings.freedom(pos);
   details.add(new Detail(candidate,group.id(),gfit,lex,fit,ret,score,reason));
  }}
  if(settings.freedom(pos)>0&&!pos.isEmpty()){
   var seen=new HashSet<String>(); details.stream().filter(d->d.reason().equals("ELIGIBLE")||d.reason().equals("ROBERT_GROUP_SOURCE_SENSE_CONFLICT")||d.reason().equals("FORBIDDEN_LEXICAL_RELATION")).forEach(d->seen.add(d.candidate()));
   double freedom=settings.freedom(pos);String tag=pos.equals("a")?"ADJ":"NOUN";
   var proposed=new LinkedHashSet<String>();
   if(!source.isEmpty()){proposed.addAll(evidence.members(source));for(String sense:evidence.neighbors(source))proposed.addAll(evidence.members(sense));}
   else for(String sense:evidence.sourceSenses(original,pos))proposed.addAll(evidence.members(sense));
   var directNeighbors=disco.neighbors(original, (int)Math.ceil(100*freedom));
   proposed.addAll(directNeighbors);
   if(Boolean.getBoolean("ivorama.lexical.debug"))System.err.printf("LEXICAL_DISCOVERY word=%s pos=%s freedom=%d source=%s robert=%d directDisco=%d combined=%d%n",original,pos,settings.semanticFreedom(pos),source,details.size(),directNeighbors.size(),proposed.size());
   for(String raw:proposed){
    if(!raw.matches("[\\p{L}’-]+"))continue;
    String candidate=morph.lemma(raw,tag).orElseGet(()->pos.equals("a")&&!morph.paradigm(raw,"ADJ").isEmpty()?raw:"");
    if(candidate.isEmpty()||candidate.equals(original)||!seen.add(candidate))continue;
    double lex=disco.wordSimilarity(original,candidate),fit=disco.cosine(disco.vector(candidate),ctx),ret=originalFit>0?Math.min(1,fit/originalFit):0;
    String reason=NeighborPolicy.reason(freedom,lex,fit,ret,originalFit,settings.effectiveRetention(pos),weights.containsKey(candidate),evidence.sourceSenses(original,pos).stream().anyMatch(sense->forbidden(sense,candidate,pos)));
    if(pos.equals("n")&&evidence.attestedAnimate(original)&&!evidence.possibleAnimate(candidate))reason="ANIMATE_CATEGORY_CONFLICT";
    details.add(new Detail(candidate,"DISCO_NEIGHBOR",lex,lex,fit,ret,Math.sqrt(lex*(.5*lex+.5*fit))*.9,reason));
   }
  }
  var relation = new ParagraphRelation(original, paragraph);
  details.replaceAll(d->{if(!d.reason().equals("ELIGIBLE"))return d;String problem=FunctionalCompatibility.reason(a,token,original,d.candidate(),source,evidence,morph);if(problem.isEmpty()&&settings.freedom(pos)>0&&d.retention()<.65)problem="MINIMUM_CONTEXT_RETENTION";if(problem.isEmpty()&&settings.freedom(pos)>0)problem=relationalReason(token,a,original,d.candidate(),relation);return problem.isEmpty()?d:new Detail(d.candidate(),d.group(),d.groupFit(),d.lexicalFit(),d.contextFit(),d.retention(),d.score(),problem);});
  details.sort(Comparator.comparingDouble(Detail::score).reversed().thenComparing(Detail::candidate));var eligible=details.stream().filter(d->d.reason().equals("ELIGIBLE")).toList();String chosen="";if(!eligible.isEmpty()&&settings.semanticFreedom(pos)>0){chosen=CreativeSelection.order(eligible,settings.freedom(pos)).get(0).candidate();}else if(!eligible.isEmpty()){var top=eligible.get(0);var other=eligible.stream().filter(d->!d.candidate().equals(top.candidate())).findFirst();if(other.isEmpty()||(top.score()-other.get().score())/Math.max(1e-12,top.score())>=settings.effectiveMargin(pos))chosen=top.candidate();}var result=new Analysis(List.copyOf(ranked),List.copyOf(details),source,chosen);cache.put(key,result);return result;
 }
 private String relationalReason(TokenData target,SentenceAnalysis a,String original,String candidate,ParagraphRelation paragraph){
  double beforeSum=0,afterSum=0;
  for(var anchor:a.tokens()){
   if(!content(anchor)||anchor.index()==target.index()||!(anchor.index()==target.headIndex()||anchor.headIndex()==target.index()))continue;
   String term=lemma(anchor);if(term.equals(original))continue;
   double before=disco.wordSimilarity(original,term),after=disco.wordSimilarity(candidate,term);
   // A strong local relation must not disappear. Weak evidence is not a veto.
   if(before>=.08 && after<before*.50)return "DIRECT_DEPENDENCY_DEGRADATION";
   if(before>=.04){beforeSum+=before;afterSum+=after;}
  }
  if(beforeSum>=.08&&afterSum<beforeSum*.70)return "AGGREGATE_DEPENDENCY_DEGRADATION";
  return paragraph.degrades(candidate) ? "DOCUMENT_CONTEXT_DEGRADATION" : "";
 }
 // The paragraph prototype and source cosine are invariant within a lexical attempt.
 private final class ParagraphRelation {
  private final String original;
  private final Map<String,Double> weights;
  private DiscoWordSpace.ContextVector prototype;
  private double originalFit;

  private ParagraphRelation(String original, Map<String,Double> weights) {
   this.original = original;
   this.weights = weights;
  }

  private boolean degrades(String candidate) {
   if (weights.isEmpty()) return false;
   if (prototype == null) {
    prototype = disco.contextVector(weights);
    originalFit = disco.cosine(disco.vector(original), prototype);
   }
   return originalFit >= .08 && disco.cosine(disco.vector(candidate), prototype) < originalFit * .55;
  }
 }
 @Override public boolean compatibleAddition(TokenData token,String current,String candidate,SentenceAnalysis a){
  String base=morph.lemma(current,"ADJ").orElse(current);
  return !settings.editsDisabled("a")
    && !evidence.redundantAdjective(base, candidate)
    && evidence.sourceSenses(base, "a").stream()
        .noneMatch(sense -> !evidence.forbiddenRelation(sense, candidate, "a").isEmpty())
    && relationalReason(token, a, base, candidate, new ParagraphRelation(base, paragraphContext(base, a))).isEmpty();
 }
 // Creative variation permits Robert-attested generalisation; contradiction and part/whole stay vetoes.
 private boolean forbidden(String source,String candidate,String pos){return !evidence.forbiddenRelation(source,candidate,pos,evidence.sourceSenses(candidate,pos).contains(source),settings.semanticFreedom(pos)>0).isEmpty();}
 private boolean crossSenseProof(String original,String candidate,String pos,String source){boolean forward=index.lookup(original).stream().anyMatch(m->!m.inverse()&&m.relation().pos().equals(pos)&&m.candidate().equals(candidate));boolean reverse=index.lookup(candidate).stream().anyMatch(m->!m.inverse()&&m.relation().pos().equals(pos)&&m.candidate().equals(original));return forward&&reverse&&evidence.explicitNearSynonym(source,candidate,pos);}
 public List<GroupScore> groupScores(TokenData t,SentenceAnalysis a){return analyze(t,a).groups();}
 public List<Detail> details(TokenData t,SentenceAnalysis a){return analyze(t,a).details();}
 public List<String> candidates(TokenData t,SynonymRegister r,SentenceAnalysis a){
  if(a==null||pos(t).isEmpty()||settings.editsDisabled(pos(t))||LexicalSafety.isProtectedExpression(a,t)||GrammaticalScope.quoted(a,t)||GrammaticalScope.operator(t))return List.of();
  var result=analyze(t,a);if(settings.semanticFreedom(pos(t))>0)return CreativeSelection.order(result.details().stream().filter(d->d.reason().equals("ELIGIBLE")).toList(),settings.freedom(pos(t))).stream().map(Detail::candidate).toList();String selected=result.selected();return selected.isEmpty()?List.of():List.of(selected);
 }
 public CandidateDecision assess(TokenData t,SynonymRegister r,String candidate,SentenceAnalysis a){
  if (settings.editsDisabled(pos(t))) return CandidateDecision.reject("SEMANTIC_FREEDOM_DISABLED");
  if(a==null||pos(t).isEmpty()||LexicalSafety.isProtectedExpression(a,t)||GrammaticalScope.quoted(a,t)||GrammaticalScope.operator(t))return CandidateDecision.reject("NO_CONTEXT_OR_PROTECTED_POS");if(r!=SynonymRegister.COURANT)return CandidateDecision.reject("DISCO_REGISTER_NOT_ATTESTED");var result=analyze(t,a);var d=result.details().stream().filter(x->x.candidate().equals(candidate)).min(Comparator.comparing((Detail x)->!x.reason().equals("ELIGIBLE")).thenComparing(Comparator.comparingDouble(Detail::score).reversed()));if(d.isEmpty())return CandidateDecision.reject("NOT_IN_LEXICAL_INVENTORY");var x=d.get();boolean accepted=settings.semanticFreedom(pos(t))>0?x.reason().equals("ELIGIBLE"):result.selected().equals(candidate);return new CandidateDecision(accepted,accepted?(settings.semanticFreedom(pos(t))==0?"ROBERT_GROUP_DISCO_SUPPORTED":(x.group().equals("DISCO_NEIGHBOR")?"DISCO_CONTEXTUAL_VARIATION":"ROBERT_CONTEXTUAL_VARIATION")):x.reason().equals("ELIGIBLE")?"CANDIDATE_RANK_AMBIGUOUS":x.reason(),x.score(),result.sourceSense(),x+"; groups="+result.groups()+"; semantic_freedom="+settings.semanticFreedom(pos(t))+"; not_probability");}
 public Optional<String> synonym(TokenData t,SynonymRegister r){return Optional.empty();}public boolean supportsRegisters(){return true;}
}
