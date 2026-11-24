package fr.ivorama.cognitive;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** Proxy HTTP standard pour tunnels HTTPS ; aucune désactivation de TLS ni redirection externe. */
public final class WebHttpClientFactory {
 private WebHttpClientFactory(){}
 public static HttpClient create(Map<String,String> environment){
  var builder=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(6));
  // Les options proxy natives du JDK restent prioritaires.
  if(System.getProperty("https.proxyHost")==null){
   String value=environment.getOrDefault("HTTPS_PROXY",environment.getOrDefault("https_proxy",""));
   if(!value.isBlank())builder.proxy(selector(value,environment.getOrDefault("NO_PROXY",environment.getOrDefault("no_proxy",""))));
  }
  return builder.build();
 }
 static ProxySelector selector(String value,String noProxy){
  URI uri;
  try{uri=URI.create(value);}catch(IllegalArgumentException ex){throw new IllegalArgumentException("Configuration du proxy HTTPS invalide");}
  if(!"http".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("","/").contains(uri.getPath())||uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException("Proxy HTTP sans identifiants requis ; utiliser la configuration native du JDK pour les autres cas");
  var proxy=new Proxy(Proxy.Type.HTTP,InetSocketAddress.createUnresolved(uri.getHost(),uri.getPort()==-1?80:uri.getPort()));
  List<String> excluded=Arrays.stream(noProxy.toLowerCase(Locale.ROOT).split(",")).map(String::strip).filter(s->!s.isEmpty()).toList();
  return new ProxySelector(){
   public List<Proxy> select(URI target){
    String host=target.getHost();if(host==null)throw new IllegalArgumentException("URL sans hôte");host=host.toLowerCase(Locale.ROOT);
    for(String item:excluded){String candidate=item.startsWith(".")?item.substring(1):item;if(item.equals("*")||host.equals(candidate)||item.startsWith(".")&&host.endsWith(item))return List.of(Proxy.NO_PROXY);}
    return List.of(proxy);
   }
   public void connectFailed(URI target,SocketAddress address,java.io.IOException error){}
  };
 }
}
