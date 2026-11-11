package li.cil.oc.vm.json;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BoundedJsonTest {
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static String repeat(String s,int n){return String.join("",Collections.nCopies(n,s));}
    static void rejects(Runnable r){try{r.run();fail("Expected bounded rejection");}catch(IllegalArgumentException expected){}}
    static void early(String s,BoundedJson.Limits limits){
        final int[] called={0};
        rejects(()->BoundedJson.object(bytes(s),limits,(text,policy)->{called[0]++;return BoundedJson.materialize(text,policy);}));
        assertEquals("Invalid input must never reach JSON-java materialization",0,called[0]);
    }
    static Map<String,Object> parse(String s){return BoundedJson.object(s,BoundedJson.QMP);}
    @Test public void validTypedTreeAndExactLongValues(){
        Map<String,Object> m=parse("{\"a\":[null,true,false,\"ok\",0,-1,9223372036854775807,-9223372036854775808]}");
        List<?> a=BoundedJson.array(m.get("a"));assertNull(a.get(0));assertEquals(true,a.get(1));assertEquals("ok",a.get(3));
        assertEquals(Long.valueOf(Long.MAX_VALUE),a.get(6));assertEquals(Long.valueOf(Long.MIN_VALUE),a.get(7));
    }
    @Test public void preflightActuallyPrecedesLibrary(){
        final int[] called={0};
        assertEquals(Long.valueOf(1),BoundedJson.object(bytes("{\"n\":1}"),BoundedJson.QMP,(s,l)->{called[0]++;return BoundedJson.materialize(s,l);}).get("n"));
        assertEquals(1,called[0]);
        early("{\"x\":0,\"\\u0078\":1}",BoundedJson.QMP);
    }
    @Test public void duplicateEscapedKeysAtAnyDepthAreRejected(){
        early("{\"x\":0,\"x\":1}",BoundedJson.QMP);
        early("{\"x\":{\"a\":0,\"\\u0061\":1}}",BoundedJson.QMP);
    }
    @Test public void malformedGrammarNeverReachesMaterialization(){
        for(String s:new String[]{"{'x':1}","{x:1}","{\"x\":1,}","{\"x\":[1,]}","{\"x\":[,1]}","{\"x\":NaN}",
            "{\"x\":Infinity}","{\"x\":True}","{\"x\":undefined}","{\"x\":/*c*/1}","{\"x\":0}//comment",
            "{\"x\":0} {}","{\"x\":0}\u0000","[0]","{} trailing","{\"x\":falsefalse}"})early(s,BoundedJson.QMP);
    }
    @Test public void integerLexemesAndOverflowAreRejectedBeforeLibrary(){
        for(String n:new String[]{"+1","01","-01","00","1.0","1e0","1E2",".1","--1","-",
            "9223372036854775808","-9223372036854775809","12345678901234567890"})
            early("{\"n\":"+n+"}",BoundedJson.QMP);
    }
    @Test public void numericStringsAreNotIntegers(){
        rejects(()->BoundedJson.integer(parse("{\"n\":\"1\"}").get("n")));
        rejects(()->BoundedJson.bool(parse("{\"n\":\"false\"}").get("n")));
    }
    @Test public void legacyNegativeZeroPolicyIsExplicit(){
        assertEquals(Long.valueOf(0),parse("{\"n\":-0}").get("n"));
        early("{\"n\":-0}",BoundedJson.CONTROL);
        early("{\"n\":-1}",BoundedJson.CONTROL);
        assertEquals(Long.valueOf(0),BoundedJson.object("{\"n\":0}",BoundedJson.CONTROL).get("n"));
    }
    @Test public void malformedUtf8FailsBeforeLibrary(){
        for(byte[] b:new byte[][]{{(byte)0xc3,40},{(byte)0xe0,(byte)0x80,(byte)0x80},
            {(byte)0xed,(byte)0xa0,(byte)0x80},{(byte)0xf0,(byte)0x9f,(byte)0x8d}}){
            final int[] count={0};
            rejects(()->BoundedJson.object(b,BoundedJson.QMP,(s,l)->{count[0]++;return BoundedJson.materialize(s,l);}));
            assertEquals(0,count[0]);
        }
    }
    @Test public void stringAndKeyLimitsAreEnforcedWhileScanning(){
        assertEquals(4096,BoundedJson.string(parse("{\"x\":\""+repeat("a",4096)+"\"}").get("x")).length());
        early("{\"x\":\""+repeat("a",4097)+"\"}",BoundedJson.QMP);
        assertTrue(parse("{\""+repeat("a",80)+"\":0}").containsKey(repeat("a",80)));
        early("{\""+repeat("a",81)+"\":0}",BoundedJson.QMP);
        early("{\""+repeat("\\u0061",81)+"\":0}",BoundedJson.QMP);
    }
    @Test public void escapedAndRawControlsHaveDistinctRules(){
        String decoded=BoundedJson.string(parse("{\"x\":\"\\b\\f\\n\\r\\t\\/\\\\\\\"\\u0000\"}").get("x"));
        assertEquals(9,decoded.length());
        for(char c=0;c<32;c++)early("{\"x\":\""+c+"\"}",BoundedJson.QMP);
        for(String s:new String[]{"\\x","\\v","\\u12G4","\\u12"})early("{\"x\":\""+s+"\"}",BoundedJson.QMP);
    }
    @Test public void unicodeCountsUtf16UnitsLikePreviousContract(){
        String emoji="\ud83d\ude00";String value=repeat(emoji,2048);
        assertEquals(4096,BoundedJson.string(parse("{\"x\":\""+value+"\"}").get("x")).length());
        early("{\"x\":\""+value+emoji+"\"}",BoundedJson.QMP);
        assertEquals(1,BoundedJson.string(parse("{\"x\":\"\\ud800\"}").get("x")).length());
        rejects(()->BoundedJson.utf8Bytes("{\"x\":\"\ud800\"}",16384,16384));
    }
    static String array(int n){return "["+String.join(",",Collections.nCopies(n,"0"))+"]";}
    static String object(int n){List<String> members=new ArrayList<>();for(int i=0;i<n;i++)members.add("\"k"+i+"\":0");return "{"+String.join(",",members)+"}";}
    @Test public void arrayAndObjectMemberBoundsPrecedeAllocation(){
        assertEquals(32,BoundedJson.array(parse("{\"a\":"+array(32)+"}").get("a")).size());
        early("{\"a\":"+array(33)+"}",BoundedJson.QMP);
        assertEquals(32,parse(object(32)).size());early(object(33),BoundedJson.QMP);
    }
    static String nest(int n,String inner){for(int i=0;i<n;i++)inner="["+inner+"]";return "{\"v\":"+inner+"}";}
    @Test public void depthCountsRootAsZeroIncludingScalarLeaves(){
        parse(nest(7,"0"));early(nest(8,"0"),BoundedJson.QMP);
        parse(nest(7,"[]"));early(nest(8,"[]"),BoundedJson.QMP);
    }
    @Test public void exact256NodesThen257IsRejectedEarly(){
        List<String> values=new ArrayList<>();for(int i=0;i<7;i++)values.add(array(32));values.add(array(22));
        parse("{\"v\":["+String.join(",",values)+"]}");
        values.set(7,array(23));early("{\"v\":["+String.join(",",values)+"]}",BoundedJson.QMP);
    }
    @Test public void byteBoundIsIndependentOfJavaCharacters(){
        BoundedJson.Limits l=new BoundedJson.Limits(64,64,8,256,32,32,80,4096,19,false,true);
        String s="{\"x\":0}"+repeat(" ",57);assertEquals(64,bytes(s).length);BoundedJson.object(s,l);
        early(s+" ",l);early("{\"x\":\""+repeat("\u00e9",30)+"\"}",l);
    }
    @Test public void allStandardEscapesAndWhitespaceAreAccepted(){
        parse(" \r\n\t{\"x\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0061\"} \n");
        early("\ufeff{}",BoundedJson.QMP);early("\u000b{}",BoundedJson.QMP);
    }
}
