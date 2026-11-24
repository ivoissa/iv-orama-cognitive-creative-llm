package com.ivorama.nlp.transform;
import java.util.*;import java.util.function.Function;
/** Bounded LRU; failed computations are not cached. */
final class BoundedCache<K,V>{
 private final int limit;private final LinkedHashMap<K,V> entries=new LinkedHashMap<>(16,.75f,true);private long hits,misses,evictions;
 BoundedCache(int limit){if(limit<1)throw new IllegalArgumentException();this.limit=limit;}
 synchronized V get(K key,Function<K,V> compute){V value=entries.get(key);if(value!=null){hits++;return value;}misses++;value=Objects.requireNonNull(compute.apply(key));entries.put(key,value);if(entries.size()>limit){entries.remove(entries.keySet().iterator().next());evictions++;}return value;}
 synchronized void clear(){entries.clear();}
 synchronized String metrics(){return "hits="+hits+" misses="+misses+" evictions="+evictions+" size="+entries.size();}
}
