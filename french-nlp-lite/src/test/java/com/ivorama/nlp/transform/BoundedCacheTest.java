package com.ivorama.nlp.transform;
import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;import java.util.concurrent.atomic.AtomicInteger;
class BoundedCacheTest {
 @Test void preservesValuesAndEvictsLeastRecentlyUsed(){var c=new BoundedCache<String,Double>(2);var calls=new AtomicInteger();java.util.function.Function<String,Double> f=k->{calls.incrementAndGet();return (double)k.length();};assertEquals(1d,c.get("a",f));assertEquals(2d,c.get("bb",f));assertEquals(1d,c.get("a",f));assertEquals(3d,c.get("ccc",f));assertEquals(1d,c.get("a",f));assertEquals(2d,c.get("bb",f));assertEquals(4,calls.get());}
 @Test void zeroAndEmptyAreCachedButExceptionsAreNot(){var c=new BoundedCache<String,String>(2);assertThrows(IllegalStateException.class,()->c.get("x",k->{throw new IllegalStateException();}));assertEquals("",c.get("x",k->""));assertEquals("",c.get("x",k->fail("must be cached")));}
}
