package fr.ivorama.cognitive;
import java.util.*;
/** P0.1: adversarial certificate constructor tests on real core classes. */
public final class ProvenanceContractP0Probe {
  private static int checks;
  private static void ok(boolean b,String reason){checks++;if(!b)throw new AssertionError("P0.1: "+reason);}
  private static void reject(Runnable operation,String reason){
    boolean thrown=false;
    try { operation.run(); } catch(IllegalArgumentException e){thrown=true;}
    ok(thrown,reason);
  }
  public static int runAll(){
    checks=0;
    Formula a=Formula.atom("a"), b=Formula.atom("b");
    Formula tautology=Formula.implies(a,a);
    SupportAnalyzer.Result support=new SupportAnalyzer.Result(SupportAnalyzer.Status.COMPLETE,List.of(),0,"empty");
    ProvenanceAudit.Source source=new ProvenanceAudit.Source("P1","declared",KnowledgeBase.Kind.ASSERTION);
    ProvenanceAudit.Dependency dependency=new ProvenanceAudit.Dependency(a,List.of(source));
    var premiseFree=new Proof.ImplicationIntroduction(a,new Proof.Assumption(a));
    var dependent=new Proof.Assumption(a);
    reject(()->new ProvenanceAudit.Certificate(tautology,ProvenanceAudit.Basis.FORMAL_THEOREM,
       true,Optional.empty(),List.of(),1,"forged",support),"formal theorem missing formal proof");
    reject(()->new ProvenanceAudit.Certificate(tautology,ProvenanceAudit.Basis.FORMAL_THEOREM,
       false,Optional.empty(),List.of(),1,"forged",support),"formal theorem with false checked flag");
    reject(()->new ProvenanceAudit.Certificate(tautology,ProvenanceAudit.Basis.FORMAL_THEOREM,
       true,Optional.of(dependent),List.of(),1,"forged",support),"formal theorem with unauthorized assumption");
    reject(()->new ProvenanceAudit.Certificate(b,ProvenanceAudit.Basis.FORMAL_THEOREM,
       true,Optional.of(premiseFree),List.of(),1,"forged",support),"formal theorem wrong goal");
    reject(()->new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.PROOF_CONDITIONAL,
       true,Optional.of(dependent),List.of(),1,"forged",support),"conditional proof missing dependencies");
    reject(()->new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.PROOF_CONDITIONAL,
       true,Optional.of(dependent),List.of(dependency,dependency),1,"forged",support),"duplicate dependencies");
    reject(()->new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.PROOF_CONDITIONAL,
       false,Optional.of(dependent),List.of(dependency),1,"forged",support),"proof present but unverified");
    reject(()->new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.PROOF_CONDITIONAL,
       true,Optional.empty(),List.of(dependency),1,"forged",support),"conditional proof absent");
    reject(()->new ProvenanceAudit.Certificate(b,ProvenanceAudit.Basis.PROOF_CONDITIONAL,
       true,Optional.of(dependent),List.of(dependency),1,"forged",support),"conditional proof wrong goal");
    reject(()->new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.DIRECT_UNVERIFIED,
       false,Optional.empty(),List.of(),1,"forged",support),"direct assertion must cite goal");
    reject(()->new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.DIRECT_UNVERIFIED,
       true,Optional.of(premiseFree),List.of(dependency),1,"forged",support),"direct assertion incompatible proof");
    reject(()->new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.MODEL_ONLY_CONDITIONAL,
       true,Optional.of(dependent),List.of(dependency),1,"forged",support),"model only must not claim proof");
    reject(()->new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.NOT_ESTABLISHED,
       false,Optional.empty(),List.of(dependency),1,"forged",support),"not established carries dependencies");
    var theorem=new ProvenanceAudit.Certificate(tautology,ProvenanceAudit.Basis.FORMAL_THEOREM,
       true,Optional.of(premiseFree),List.of(),1,"valid",support);
    ok(theorem.hasPremiseFreeFormalCertificate(),"valid identity implication theorem");
    var conditional=new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.PROOF_CONDITIONAL,
       true,Optional.of(dependent),List.of(dependency),1,"valid",support);
    ok(conditional.proofChecked() && conditional.dependencies().size()==1,"valid assumption-backed proof");
    ok(!conditional.hasPremiseFreeFormalCertificate(),"conditional is not theorem");
    var direct=new ProvenanceAudit.Certificate(a,ProvenanceAudit.Basis.DIRECT_UNVERIFIED,
       true,Optional.of(dependent),List.of(dependency),1,"valid",support);
    ok(direct.proofChecked() && !direct.hasPremiseFreeFormalCertificate(),"direct assumption remains unverified");
    var model=new ProvenanceAudit.Certificate(b,ProvenanceAudit.Basis.MODEL_ONLY_CONDITIONAL,
       false,Optional.empty(),List.of(),1,"valid",support);
    ok(!model.proofChecked(),"model-only never claims formal proof");
    var notEstablished=new ProvenanceAudit.Certificate(b,ProvenanceAudit.Basis.NOT_ESTABLISHED,
       false,Optional.empty(),List.of(),1,"valid",support);
    ok(!notEstablished.proofChecked(),"unestablished never claims proof");
    ok(!theorem.hasEmpiricallyVerifiedPremises(),"theorem does not certify empirical truth");
    ok(!conditional.hasEmpiricallyVerifiedPremises(),"conditional does not certify empirical truth");
    return checks;
  }
  public static void main(String[] args){System.out.println("P0.1 REAL-CORE CONTRACT PASS - "+runAll()+" checks");}
}
