package li.cil.oc.vm.simulated;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

public class QmpBoundaryRegressionTest {
    static String reply(){return "{\"return\":{},\"id\":\""+UUID.randomUUID()+"\"}";}
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static String pad(String s,int total){return s+String.join("",Collections.nCopies(total-s.length()," "));}
    static void bad(String wire){
        QmpCodec codec=new QmpCodec();
        QmpFixtureTest.Work work=()->codec.feed(bytes(wire),m->{});
        try{work.run();fail("Expected rejection");}catch(IllegalArgumentException expected){}catch(Exception impossible){throw new AssertionError(impossible);}
        assertEquals(0,codec.retainedBytes());
        try{codec.feed(bytes(QmpFixtureTest.GREETING),m->{});fail("Failure must close parser");}catch(IllegalStateException expected){}
    }
    @Test public void exactLineBoundIncludesCrLfAndNextByteFails(){
        String exact=pad(reply(),QmpCodec.MAX_LINE-2)+"\r\n";List<QmpCodec.Message> messages=new ArrayList<>();
        QmpCodec c=new QmpCodec();c.feed(bytes(exact),messages::add);assertEquals(1,messages.size());
        bad(pad(reply(),QmpCodec.MAX_LINE-1)+"\r\n");
    }
    @Test public void exactLifetimeBytesThenLimitPlusOneFails(){
        byte[] line=bytes(pad(reply(),QmpCodec.MAX_LINE-2)+"\r\n");QmpCodec c=new QmpCodec();int[] count={0};
        for(int i=0;i<16;i++)c.feed(line,m->count[0]++);assertEquals(262144,c.lifetimeBytes());assertEquals(16,count[0]);
        try{c.feed(new byte[]{32},m->{});fail();}catch(IllegalArgumentException expected){}assertEquals(0,c.retainedBytes());
    }
    @Test public void partialChunksPreserveFramingAndNoEarlyPublication(){
        byte[] line=bytes(reply()+"\r\n");QmpCodec c=new QmpCodec();int[] n={0};
        for(int i=0;i<line.length-1;i++){c.feed(new byte[]{line[i]},m->n[0]++);assertEquals(0,n[0]);}
        c.feed(new byte[]{10},m->n[0]++);assertEquals(1,n[0]);assertEquals(0,c.retainedBytes());
    }
    @Test public void unknownFieldsWrongTypesAndNoncanonicalIdsFail(){
        bad("{\"return\":{},\"id\":\""+UUID.randomUUID()+"\",\"extra\":0}\r\n");
        bad("{\"return\":{},\"id\":1}\r\n");bad("{\"return\":{},\"id\":\"NOT-A-UUID\"}\r\n");
        bad(QmpFixtureTest.GREETING.replace("\"major\":9","\"major\":\"9\""));
        bad(QmpFixtureTest.GREETING.replace("\"major\":9","\"major\":9.0"));
        bad(QmpFixtureTest.GREETING.replace("\"major\":9","\"major\":9e0"));
    }
    @Test public void escapedDuplicateAndTrailingDataFailClosed(){
        bad("{\"return\":{},\"id\":\""+UUID.randomUUID()+"\",\"\\u0069d\":\""+UUID.randomUUID()+"\"}\r\n");
        bad(reply()+" {}\r\n");bad(reply()+"\n");bad(reply()+"\r\n"+"{bad}\r\n");
    }
    @Test public void callbackFailureClosesAndClearsParser(){
        QmpCodec c=new QmpCodec();
        try{c.feed(bytes(reply()+"\r\n"),m->{throw new IllegalArgumentException("sink fixture");});fail();}catch(IllegalArgumentException expected){}
        assertEquals(0,c.retainedBytes());try{c.feed(new byte[]{32},m->{});fail();}catch(IllegalStateException expected){}
    }
}
