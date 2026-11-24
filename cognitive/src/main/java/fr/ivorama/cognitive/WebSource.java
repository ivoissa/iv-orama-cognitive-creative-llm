package fr.ivorama.cognitive;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Recherche HTTP uniquement sur des API HTTPS de fournisseurs connus. */
public final class WebSource {
    public static final class OperationError extends IllegalStateException{public OperationError(String message){super(message);}}
    private WebSource(){}
    public record Hit(URI url,String title,String excerpt,Instant retrievedAt,String provider){
        public Hit{Objects.requireNonNull(url);Objects.requireNonNull(title);Objects.requireNonNull(excerpt);Objects.requireNonNull(retrievedAt);Objects.requireNonNull(provider);}
    }
    public interface Provider { List<Hit> search(String terms) throws Exception; }
    private static HttpClient cachedClient;
    private static synchronized HttpClient client(){if(cachedClient==null)cachedClient=WebHttpClientFactory.create(System.getenv());return cachedClient;}
    private static String getJson(URI uri,String expectedHost,String token) throws Exception {
        if(!"https".equals(uri.getScheme())||!expectedHost.equals(uri.getHost())||uri.getPort()!=-1||uri.getUserInfo()!=null)
            throw new SecurityException("endpoint API non autorisé");
        HttpRequest.Builder request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(9))
            .header("Accept","application/json")
            .header("User-Agent","IV-ORAMA-Cognitive-Research/0.1 (educational; configurable)")
            .GET();
        if(token!=null)request.header("X-Subscription-Token",token);
        HttpResponse<InputStream> response=client().send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream stream=response.body()){
            if(response.statusCode()!=200)throw new java.io.IOException("API HTTP "+response.statusCode());
            String contentType=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if(!contentType.contains("json"))throw new java.io.IOException("API: contenu non JSON");
            byte[] bytes=stream.readNBytes(262145);
            if(bytes.length>262144)throw new java.io.IOException("API: réponse trop volumineuse");
            return new String(bytes,StandardCharsets.UTF_8);
        }
    }
    private static String searchTerms(String terms){
        if(terms==null||terms.isBlank()||terms.length()>180||terms.chars().anyMatch(c->c<32))
            throw new IllegalArgumentException("requête recherche invalide");
        return URLEncoder.encode(terms, StandardCharsets.UTF_8);
    }
    private static String trim(String text){return text.length()>1800?text.substring(0,1800):text;}
    private static Instant now(){return Instant.now();}
    public static final class Wikipedia implements Provider {
        @Override public List<Hit> search(String terms) throws Exception {
            String query=searchTerms(terms);
            URI api=URI.create("https://fr.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch="
                  +query+"&gsrlimit=3&gsrnamespace=0&prop=extracts%7Cpageprops&ppprop=disambiguation&exintro=1&explaintext=1&exchars=1200&exlimit=3&format=json&formatversion=2&utf8=1");
            String data=getJson(api,"fr.wikipedia.org",null);
            try{return parseSearchResponse(data,now());}catch(OperationError|IllegalArgumentException ex){throw new java.io.IOException("Réponse API Wikipédia inexploitable",ex);}
        }
        public static List<Hit> parseSearchResponse(String json,Instant retrievedAt){
            Object parsed=MiniJson.parse(json);if(!(parsed instanceof Map<?,?>))throw new IllegalArgumentException("Wikipédia : objet JSON requis");
            Map<String,Object> root=MiniJson.obj(parsed);
            if(root.containsKey("error"))throw new OperationError("Wikipédia : erreur API");
            if(!root.containsKey("query")){if(root.containsKey("batchcomplete"))return List.of();throw new IllegalArgumentException("Wikipédia : réponse sans résultat ni fin de lot");}
            if(!(root.get("query") instanceof Map<?,?> query)||!(query.get("pages") instanceof List<?>))throw new IllegalArgumentException("Wikipédia : pages absentes ou mal formées");
            List<Hit> hits=new ArrayList<>();
            for(Object page:MiniJson.arr(MiniJson.obj(root.get("query")).get("pages"))){
                Map<String,Object> info=MiniJson.obj(page);
                Object pageId=info.get("pageid");
                if(!(pageId instanceof Number id) || id.longValue()<=0||id.doubleValue()!=id.longValue()||info.containsKey("missing")||MiniJson.obj(info.get("pageprops")).containsKey("disambiguation"))continue;
                String extract=MiniJson.str(info.get("extract"));
                if(extract.isBlank())continue;
                hits.add(new Hit(URI.create("https://fr.wikipedia.org/?curid="+id.longValue()),
                    MiniJson.str(info.get("title")),trim(extract),retrievedAt,"Wikipedia API"));
                if(hits.size()==3)break;
            }
            return List.copyOf(hits);
        }
    }
    /** Recherche Web générique, optionnelle. Nécessite BRAVE_SEARCH_API_KEY. */
    public static final class Brave implements Provider {
        private final String apiKey;
        public Brave(String apiKey){
            if(apiKey==null||apiKey.isBlank()||apiKey.contains("\n")||apiKey.contains("\r"))throw new IllegalArgumentException("clé API invalide");
            this.apiKey=apiKey;
        }
        @Override public List<Hit> search(String terms) throws Exception {
            URI api=URI.create("https://api.search.brave.com/res/v1/web/search?q="+searchTerms(terms)+"&count=4");
            Map<String,Object> root=MiniJson.obj(MiniJson.parse(getJson(api,"api.search.brave.com",apiKey)));
            List<Hit> hits=new ArrayList<>();
            for(Object item:MiniJson.arr(MiniJson.obj(root.get("web")).get("results"))){
                Map<String,Object> info=MiniJson.obj(item);
                String url=MiniJson.str(info.get("url"));
                URI uri;
                try{uri=URI.create(url);}catch(IllegalArgumentException e){continue;}
                if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)continue;
                String text=MiniJson.str(info.get("description"));
                if(!text.isBlank())hits.add(new Hit(uri,MiniJson.str(info.get("title")),trim(text),now(),"Brave Search"));
                if(hits.size()==4)break;
            }
            return List.copyOf(hits);
        }
    }
}
