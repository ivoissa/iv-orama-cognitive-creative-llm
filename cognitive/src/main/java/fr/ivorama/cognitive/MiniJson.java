package fr.ivorama.cognitive;

import java.util.*;

/** Decodeur JSON borne, sans dépendance externe, adapté aux réponses API. */
public final class MiniJson {
    private final String data;
    private int i=0;
    private int depth=0;
    private MiniJson(String data){this.data=Objects.requireNonNull(data);}
    public static Object parse(String data){
        if(data.length()>300_000)throw new IllegalArgumentException("JSON trop volumineux");
        MiniJson p=new MiniJson(data);
        Object value=p.value();p.spaces();
        if(p.i!=data.length())throw new IllegalArgumentException("JSON: suffixe");
        return value;
    }
    private void spaces(){while(i<data.length() && " \r\t\n".indexOf(data.charAt(i))>=0)i++;}
    private char peek(){spaces();if(i>=data.length())throw new IllegalArgumentException("JSON tronqué");return data.charAt(i);}
    private void token(char c){if(peek()!=c)throw new IllegalArgumentException("JSON: attendu "+c);i++;}
    private Object value(){
        if(++depth>32)throw new IllegalArgumentException("JSON trop imbriqué");
        try {
            char c=peek();
            if(c=='"')return string();
            if(c=='{')return object();
            if(c=='[')return array();
            if(c=='t'){literal("true");return Boolean.TRUE;}
            if(c=='f'){literal("false");return Boolean.FALSE;}
            if(c=='n'){literal("null");return null;}
            if(c=='-' || Character.isDigit(c))return number();
            throw new IllegalArgumentException("JSON invalide");
        }finally{depth--;}
    }
    private void literal(String v){if(!data.startsWith(v,i))throw new IllegalArgumentException("JSON littéral");i+=v.length();}
    private Object number(){
        int start=i;
        if(data.charAt(i)=='-')i++;
        if(i>=data.length())throw new IllegalArgumentException("JSON nombre");
        if(data.charAt(i)=='0')i++;
        else {if(data.charAt(i)<'1'||data.charAt(i)>'9')throw new IllegalArgumentException("JSON nombre");while(i<data.length()&&Character.isDigit(data.charAt(i)))i++;}
        if(i<data.length()&&data.charAt(i)=='.'){i++;int s=i;while(i<data.length()&&Character.isDigit(data.charAt(i)))i++;if(s==i)throw new IllegalArgumentException("JSON nombre");}
        if(i<data.length()&&(data.charAt(i)=='e'||data.charAt(i)=='E')){i++;if(i<data.length()&&(data.charAt(i)=='+'||data.charAt(i)=='-'))i++;int s=i;while(i<data.length()&&Character.isDigit(data.charAt(i)))i++;if(s==i)throw new IllegalArgumentException("JSON exponent");}
        return Double.parseDouble(data.substring(start,i));
    }
    private String string(){
        token('"');StringBuilder s=new StringBuilder();
        while(i<data.length()){
            char c=data.charAt(i++);
            if(c=='"')return s.toString();
            if(c<32)throw new IllegalArgumentException("JSON contrôle");
            if(c=='\\'){
                if(i>=data.length())throw new IllegalArgumentException("JSON échappement");
                c=data.charAt(i++);
                switch(c){
                    case '"','\\','/' -> s.append(c);
                    case 'b' -> s.append('\b');case 'f' -> s.append('\f');
                    case 'n' -> s.append('\n');case 'r' -> s.append('\r');case 't' -> s.append('\t');
                    case 'u' -> {if(i+4>data.length())throw new IllegalArgumentException("JSON unicode");s.append((char)Integer.parseInt(data.substring(i,i+4),16));i+=4;}
                    default -> throw new IllegalArgumentException("JSON escape inconnu");
                }
            }else s.append(c);
        }
        throw new IllegalArgumentException("JSON chaîne tronquée");
    }
    private Map<String,Object> object(){
        token('{');Map<String,Object> r=new LinkedHashMap<>();
        if(peek()=='}'){i++;return r;}
        do {
            String k=string();token(':');Object v=value();
            if(r.containsKey(k))throw new IllegalArgumentException("JSON clé doublonnée");
            r.put(k,v);
            char c=peek();i++;if(c=='}')return r;if(c!=',')throw new IllegalArgumentException("JSON objet");
        }while(true);
    }
    private List<Object> array(){
        token('[');List<Object> r=new ArrayList<>();
        if(peek()==']'){i++;return r;}
        do {
            r.add(value());if(r.size()>1000)throw new IllegalArgumentException("JSON tableau trop long");
            char c=peek();i++;if(c==']')return r;if(c!=',')throw new IllegalArgumentException("JSON tableau");
        }while(true);
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> obj(Object value){
        return value instanceof Map<?,?> m?(Map<String,Object>)m:Map.of();
    }
    public static List<?> arr(Object value){return value instanceof List<?> l?l:List.of();}
    public static String str(Object value){return value instanceof String s?s:"";}
}
