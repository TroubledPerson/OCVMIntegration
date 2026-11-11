package li.cil.oc.vm.json;

import li.cil.oc.vm.internal.jsonjava20260814.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.math.*;

/**
 * Independent bounded JSON grammar preflight before JSON-java materialization.
 * No Gson implementation is copied. Keys are decoded into bounded sets during
 * preflight; value strings are counted without constructing them.
 */
public final class BoundedJson {
    public static final class Limits {
        public final int bytes, chars, depth, nodes, members, array, key, string, digits;
        public final boolean unsigned, negativeZero;
        public Limits(int bytes,int chars,int depth,int nodes,int members,int array,
                      int key,int string,int digits,boolean unsigned,boolean negativeZero) {
            if(bytes<1||bytes>196608||chars<1||chars>65536||depth<0||depth>16||
               nodes<1||nodes>32769||members<1||members>4096||array<1||array>4096||
               key<1||key>65536||string<1||string>65536||digits<1||digits>19)
                throw new IllegalArgumentException("JSON policy");
            this.bytes=bytes;this.chars=chars;this.depth=depth;this.nodes=nodes;
            this.members=members;this.array=array;this.key=key;this.string=string;
            this.digits=digits;this.unsigned=unsigned;this.negativeZero=negativeZero;
        }
    }
    // Legacy QMP accepted -0 as integer zero; CONTROL has never accepted a sign.
    public static final Limits QMP=new Limits(16384,16384,8,256,32,32,80,4096,19,false,true);
    public static final Limits CONTROL=new Limits(16384,16384,1,13,12,1,80,80,19,true,false);
    public static final Limits MANIFEST=new Limits(196608,65536,16,32769,4096,4096,65536,65536,16,false,true);
    public static final Limits SUPERVISOR=QMP;
    interface Materializer { JSONObject read(String text,Limits limits); }

    public static byte[] utf8Bytes(String text,int maxChars,int maxBytes) {
        if(text==null||text.length()>maxChars)throw new IllegalArgumentException("JSON character bound");
        try {
            ByteBuffer b=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));
            if(b.remaining()>maxBytes)throw new IllegalArgumentException("JSON byte bound");
            byte[] result=new byte[b.remaining()];b.get(result);return result;
        } catch(CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid Unicode",invalid); }
    }
    public static String utf8(byte[] data,int maxBytes) {
        if(data==null||data.length==0||data.length>maxBytes)throw new IllegalArgumentException("JSON byte bound");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString(); }
        catch(CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid UTF-8",invalid); }
    }
    public static Map<String,Object> object(String text,Limits limits) {
        return object(utf8Bytes(text,limits.chars,limits.bytes),limits);
    }
    public static Map<String,Object> object(byte[] data,Limits limits) {
        return object(data,limits,BoundedJson::materialize);
    }
    static Map<String,Object> object(byte[] data,Limits limits,Materializer materializer) {
        String text=utf8(data,limits.bytes);
        if(text.length()>limits.chars)throw new IllegalArgumentException("JSON character bound");
        new Preflight(text,limits).document(); // MUST precede any JSONObject/JSONArray construction.
        try { return objectValue(normalize(materializer.read(text,limits))); }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("Invalid bounded JSON",invalid); }
    }
    static JSONObject materialize(String text,Limits limits) {
        JSONParserConfiguration config=new JSONParserConfiguration().withStrictMode(true)
            .withOverwriteDuplicateKey(false).withMaxNestingDepth(limits.depth+2);
        JSONTokener tokener=new JSONTokener(text,config);
        JSONObject root=new JSONObject(tokener,config);
        if(tokener.nextClean()!=0)throw new IllegalArgumentException("Trailing JSON");
        return root;
    }
    private static Object normalize(Object value) {
        if(value==JSONObject.NULL)return null;
        if(value instanceof JSONObject) {
            Map<String,Object> result=new LinkedHashMap<>();
            JSONObject object=(JSONObject)value;
            for(String key:object.keySet())result.put(key,normalize(object.get(key)));
            return result;
        }
        if(value instanceof JSONArray) {
            List<Object> result=new ArrayList<>();JSONArray array=(JSONArray)value;
            for(int i=0;i<array.length();i++)result.add(normalize(array.get(i)));
            return result;
        }
        if(value instanceof Long||value instanceof Integer||value instanceof Short||value instanceof Byte)
            return Long.valueOf(((Number)value).longValue());
        if(value instanceof BigInteger)return Long.valueOf(((BigInteger)value).longValueExact());
        if(value instanceof BigDecimal)return Long.valueOf(((BigDecimal)value).toBigIntegerExact().longValueExact());
        if(value instanceof Double&&Double.doubleToRawLongBits((Double)value)==Double.doubleToRawLongBits(-0.0d))
            return Long.valueOf(0); // Only a preflight-authorized legacy -0 can produce this representation.
        if(value instanceof String||value instanceof Boolean||value==null)return value;
        throw new IllegalArgumentException("Unexpected JSON representation");
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> objectValue(Object value) {
        if(!(value instanceof Map))throw new IllegalArgumentException("Object required");
        return (Map<String,Object>)value;
    }
    public static List<?> array(Object value) {
        if(!(value instanceof List))throw new IllegalArgumentException("Array required");return (List<?>)value;
    }
    public static String string(Object value) {
        if(!(value instanceof String))throw new IllegalArgumentException("String required");return (String)value;
    }
    public static long integer(Object value) {
        if(!(value instanceof Long))throw new IllegalArgumentException("Integer required");return (Long)value;
    }
    public static boolean bool(Object value) {
        if(!(value instanceof Boolean))throw new IllegalArgumentException("Boolean required");return (Boolean)value;
    }
    public static void keys(Map<String,Object> object,String... keys) {
        if(!object.keySet().equals(new HashSet<>(Arrays.asList(keys))))throw new IllegalArgumentException("Unexpected JSON fields");
    }
    private static final class Preflight {
        final String text;final Limits limits;int pos,nodes;
        Preflight(String text,Limits limits){this.text=text;this.limits=limits;}
        void bad(){throw new IllegalArgumentException("JSON grammar/early resource bound");}
        char peek(){return pos<text.length()?text.charAt(pos):0;}
        void space(){while(pos<text.length()&&(peek()==' '||peek()=='\t'||peek()=='\r'||peek()=='\n'))pos++;}
        void take(char c){space();if(peek()!=c)bad();pos++;}
        void document(){space();if(peek()!='{')bad();value(0);space();if(pos!=text.length())bad();}
        void value(int depth) {
            if(depth>limits.depth||nodes>=limits.nodes)bad();nodes++;space();
            char c=peek();
            if(c=='{')object(depth);else if(c=='[')array(depth);else if(c=='"')string(limits.string,false);
            else if(c=='t')literal("true");else if(c=='f')literal("false");else if(c=='n')literal("null");
            else if(c=='-'||(c>='0'&&c<='9'))number();else bad();
        }
        void object(int depth) {
            take('{');space();if(peek()=='}'){pos++;return;}
            Set<String> seen=new HashSet<>();int count=0;
            while(true) {
                if(count>=limits.members)bad();space();if(peek()!='"')bad();
                String key=string(limits.key,true);if(!seen.add(key))bad();count++;
                take(':');value(depth+1);space();char c=peek();pos++;
                if(c=='}')return;if(c!=',')bad();
            }
        }
        void array(int depth) {
            take('[');space();if(peek()==']'){pos++;return;}int count=0;
            while(true) {
                if(count>=limits.array)bad();count++;value(depth+1);space();char c=peek();pos++;
                if(c==']')return;if(c!=',')bad();
            }
        }
        String string(int maximum,boolean key) {
            if(peek()!='"')bad();pos++;int units=0;
            StringBuilder out=key?new StringBuilder(Math.min(maximum,80)):null;
            while(pos<text.length()) {
                char c=text.charAt(pos++);
                if(c=='"')return key?out.toString():null;
                if(c<0x20)bad();
                if(c=='\\') {
                    if(pos>=text.length())bad();char escape=text.charAt(pos++);
                    switch(escape) {
                        case '"':case '\\':case '/':c=escape;break;
                        case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;
                        case 'r':c='\r';break;case 't':c='\t';break;
                        case 'u':
                            if(text.length()-pos<4)bad();int code=0;
                            for(int i=0;i<4;i++){char h=text.charAt(pos++);int digit=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;if(digit<0)bad();code=(code<<4)|digit;}
                            c=(char)code;break;
                        default:bad();
                    }
                }
                if(units>=maximum)bad();units++;if(key)out.append(c);
            }
            bad();return null;
        }
        void literal(String word) {
            if(!text.regionMatches(pos,word,0,word.length()))bad();pos+=word.length();
        }
        void number() {
            int start=pos;boolean negative=peek()=='-';
            if(negative){if(limits.unsigned)bad();pos++;}
            int digits=0;char first=peek();
            while(peek()>='0'&&peek()<='9') {
                if(digits>=limits.digits)bad();digits++;pos++;
            }
            if(digits==0||(first=='0'&&digits!=1))bad();
            if(negative&&first=='0'&&!limits.negativeZero)bad();
            char c=peek();if(!(c==0||c==' '||c=='\t'||c=='\r'||c=='\n'||c==','||c==']'||c=='}'))bad();
            try {Long.parseLong(text.substring(start,pos));}catch(NumberFormatException overflow){bad();}
        }
    }
    private BoundedJson(){}
}
