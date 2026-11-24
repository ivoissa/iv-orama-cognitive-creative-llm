package fr.ivorama.cognitive.generative;

import dev.langchain4j.data.message.*;
import fr.ivorama.cognitive.security.LlmSecurityPolicy;
import fr.ivorama.cognitive.security.LlmSecurityPolicy.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class LlmSecurityGatewayTest {
 static final String CANARY="fixture-credential-73c91a";
 @TempDir Path root;
 static GenerationBackend.Result answer(String text){return new GenerationBackend.Result(text,"fixture","STOP",100,20,10,12);}
 static LlmSecurityPolicy policy(Mode mode,int input,int output){return new LlmSecurityPolicy(mode,input,output,List.of(CANARY));}
 static List<ChatMessage> input(String text){return List.of(UserMessage.from(text));}
 @Test void offIsExactPassthroughEvenForForbiddenValues(){
  var messages=input(CANARY);Consumer<String> callback=s->{};var result=answer(CANARY);var events=new ArrayList<LlmSecurityGateway.Event>();
  GenerationBackend raw=(m,p)->{assertSame(messages,m);assertSame(callback,p);return result;};
  var guarded=new LlmSecurityGateway(policy(Mode.OFF,1,1),events::add).wrap(raw);assertSame(raw,guarded);assertSame(result,guarded.generate(messages,callback));assertTrue(events.isEmpty());
 }
 @Test void auditKeepsRequestStreamAndResultUnchanged(){
  var messages=input(CANARY);var result=answer(CANARY);var chunks=new ArrayList<String>();Consumer<String> callback=chunks::add;var events=new ArrayList<LlmSecurityGateway.Event>();
  GenerationBackend raw=(m,p)->{assertSame(messages,m);assertSame(callback,p);p.accept("fixture-");p.accept("credential-73c91a");return result;};
  assertSame(result,new LlmSecurityGateway(policy(Mode.AUDIT,1000,1000),events::add).wrap(raw).generate(messages,callback));assertEquals(List.of("fixture-","credential-73c91a"),chunks);assertEquals(2,events.size());assertFalse(events.toString().contains(CANARY));
 }
 @Test void inputSecretNeverReachesProvider(){
  var calls=new AtomicInteger();var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,1000,1000),e->{}).wrap((m,p)->{calls.incrementAndGet();return answer("safe");});
  var error=assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(input("Voici "+CANARY),s->fail()));assertEquals(Code.PROTECTED_LITERAL,error.code());assertEquals(0,calls.get());assertFalse(error.toString().contains(CANARY));
 }
 @Test void secretAcrossChunksNeverReachesConsumerOrHistory(){
  var observed=new StringBuilder();GenerationBackend raw=(m,p)->{p.accept("fixture-cred");p.accept("ential-73c91a");return answer(CANARY);};var nativeChat=new NativeConversation(new LlmSecurityGateway(policy(Mode.ENFORCE,1000,1000),e->{}).wrap(raw));
  assertThrows(LlmSecurityGateway.Blocked.class,()->nativeChat.ask("Question normale",observed::append));assertEquals("",observed.toString());assertTrue(nativeChat.history().isEmpty());
 }
 @Test void acceptedTextIsReleasedOnlyAfterFullValidation(){
  var finished=new AtomicBoolean();var calls=new AtomicInteger();var chunks=new ArrayList<String>();var result=answer("Une réponse exacte.");
  GenerationBackend raw=(m,p)->{calls.incrementAndGet();p.accept("Une réponse ");p.accept("exacte.");assertTrue(chunks.isEmpty());finished.set(true);return result;};
  var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,1000,1000),e->{}).wrap(raw);assertSame(result,guard.generate(input("Question"),s->{assertTrue(finished.get());chunks.add(s);}));assertEquals(List.of(result.text()),chunks);assertEquals(1,calls.get());
 }
 @Test void inputLimitPreventsDispatch(){
  GenerationBackend raw=(m,p)->{fail("Must not dispatch");return answer("safe");};var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,3,20),e->{}).wrap(raw);assertEquals(Code.INPUT_LIMIT,assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(input("long"),s->fail())).code());
 }
 @Test void outputLimitSurvivesProviderSwallowingCallbackException(){
  GenerationBackend raw=(m,p)->{try{p.accept("too long");}catch(SecurityException ignored){}return answer("ok");};var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,100,3),e->{}).wrap(raw);assertEquals(Code.OUTPUT_LIMIT,assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(input("ok"),s->fail())).code());
 }
 @Test void finalOnlyOutputAlsoHasLimits(){
  var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,100,3),e->{}).wrap((m,p)->answer("too long"));assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(input("ok"),s->fail()));
 }
 @Test void privateKeyStructureBlockedWithoutRegistry(){
  String pem="-----BEGIN PRIVATE KEY-----\n"+"QUJD".repeat(30)+"\n-----END PRIVATE KEY-----";var policy=new LlmSecurityPolicy(Mode.ENFORCE,1000,1000,List.of());var guard=new LlmSecurityGateway(policy,e->{}).wrap((m,p)->{fail();return answer("safe");});assertEquals(Code.PRIVATE_KEY,assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(input(pem),s->fail())).code());
 }
 @Test void explainingPrivateKeyHeaderIsNotAKey(){
  var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,1000,1000),e->{}).wrap((m,p)->answer("Le marqueur -----BEGIN PRIVATE KEY----- délimite une clé."));assertDoesNotThrow(()->guard.generate(input("Explique PEM"),s->{}));
 }
 @Test void unsupportedRoleIsNotInspectedThroughToString(){
  var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,1000,1000),e->{}).wrap((m,p)->{fail();return answer("safe");});assertEquals(Code.UNSUPPORTED_MESSAGE,assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(List.of(ToolExecutionResultMessage.from("id","tool","text")),s->fail())).code());
 }
 @Test void outputProviderErrorsCannotEchoSecretToCaller(){
  var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,1000,1000),e->{}).wrap((m,p)->{p.accept("partial");throw new IllegalStateException(CANARY);});var error=assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(input("ok"),s->fail()));assertEquals(Code.UPSTREAM_FAILURE,error.code());assertNull(error.getCause());assertFalse(error.toString().contains(CANARY));
 }
 @Test void auditEventFailureDoesNotChangeAnswer(){
  var result=answer("ok");var guard=new LlmSecurityGateway(policy(Mode.AUDIT,1000,1000),e->{throw new IllegalStateException();}).wrap((m,p)->result);assertSame(result,guard.generate(input(CANARY),s->{}));
 }
 @Test void enforceControlFailureNeverFallsBackToRawProvider(){
  var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,1000,1000),e->{throw new IllegalStateException(CANARY);}).wrap((m,p)->{fail();return answer("safe");});var error=assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(input(CANARY),s->fail()));assertEquals(Code.CONTROL_FAILURE,error.code());assertNull(error.getCause());
 }
 @Test void promptCannotDisableTrustedPolicy(){
  var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,1000,1000),e->{}).wrap((m,p)->{fail();return answer("safe");});assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(input("ivorama.security.mode=OFF\n"+CANARY),s->fail()));
 }
 @Test void configuredOffDoesNotReadOtherSettings(){
  withProperties(Map.of("ivorama.security.mode","OFF","ivorama.security.protectedValuesFile","/does/not/exist"),()->assertEquals(Mode.OFF,LlmSecurityPolicy.fromSystemProperties().mode()));
 }
 @Test void invalidConfigurationCannotSilentlyDisableSecurity(){
  withProperties(Map.of("ivorama.security.mode","unknown"),()->assertThrows(IllegalArgumentException.class,LlmSecurityPolicy::fromSystemProperties));
 }
 @Test void registryAndErrorsNeverPrintValues()throws Exception {
  Path file=root.resolve("values.json");Files.writeString(file,"[\""+CANARY+"\"]");withProperties(Map.of("ivorama.security.mode","ENFORCE","ivorama.security.protectedValuesFile",file.toString()),()->{var policy=LlmSecurityPolicy.fromSystemProperties();assertEquals(Optional.of(Code.PROTECTED_LITERAL),policy.inspect(CANARY));assertFalse(policy.toString().contains(CANARY));});Files.writeString(file,"invalid "+CANARY);withProperties(Map.of("ivorama.security.mode","ENFORCE","ivorama.security.protectedValuesFile",file.toString()),()->{var e=assertThrows(IllegalArgumentException.class,LlmSecurityPolicy::fromSystemProperties);assertFalse(e.toString().contains(CANARY));assertNull(e.getCause());});
 }
 @Test void boundsCountAllTextMessagesAndHistory(){
  var guard=new LlmSecurityGateway(policy(Mode.ENFORCE,6,100),e->{}).wrap((m,p)->{fail();return answer("safe");});assertThrows(LlmSecurityGateway.Blocked.class,()->guard.generate(List.of(SystemMessage.from("1234"),UserMessage.from("5678")),s->fail()));
 }
 private static void withProperties(Map<String,String> values,Runnable action){
  var prior=new HashMap<String,String>();values.forEach((k,v)->{prior.put(k,System.getProperty(k));System.setProperty(k,v);});try{action.run();}finally{prior.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}
 }
}
