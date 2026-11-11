package li.cil.oc.vm.simulated;

import li.cil.oc.vm.json.BoundedJson;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Consumer;

/** Strict bounded QMP transcript subset; it is not a general QMP client or external connection. */
public final class QmpCodec implements AutoCloseable {
 public static final int MAX_LINE=16384,MAX_TOTAL=262144,MAX_DEPTH=8,MAX_NODES=256;
 public enum Kind { GREETING,RETURN,ERROR,EVENT }
 public enum Command {
  CAPABILITIES("qmp_capabilities"),STATUS("query-status"),CONTINUE("cont"),POWER_DOWN("system_powerdown"),QUIT("quit");
  final String wire;Command(String wire){this.wire=wire;}
 }
 public static final class Message {
  public final Kind kind;public final UUID id;public final String event;public final Map<String,Object> body;
  private Message(Kind kind,UUID id,String event,Map<String,Object> body){this.kind=kind;this.id=id;this.event=event;this.body=Collections.unmodifiableMap(body);}
 }
 private final ByteArrayOutputStream line=new ByteArrayOutputStream();private int total;private boolean closed;
 public void feed(byte[] bytes,Consumer<Message> consumer){
  if(closed)throw new IllegalStateException("codec closed");
  if(bytes==null||consumer==null||bytes.length>MAX_TOTAL-total){close();throw new IllegalArgumentException("transcript byte bound");}
  total+=bytes.length;
  try {
   for(byte b:bytes){
    if(line.size()>=MAX_LINE)throw new IllegalArgumentException("QMP line byte bound");
    line.write(b);
    if(b==10){byte[] data=line.toByteArray();line.reset();if(data.length<3||data[data.length-2]!=13)throw new IllegalArgumentException("QMP CRLF framing");consumer.accept(parse(Arrays.copyOf(data,data.length-2)));}
   }
  }catch(RuntimeException failure){close();throw failure;}
 }
 public int retainedBytes(){return line.size();}
 public int lifetimeBytes(){return total;}
 @Override public void close(){closed=true;line.reset();}
 public static byte[] command(Command command,UUID id){
  Objects.requireNonNull(command);Objects.requireNonNull(id);
  return ("{\"execute\":\""+command.wire+"\",\"id\":\""+id+"\"}\r\n").getBytes(StandardCharsets.UTF_8);
 }
 @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map))throw new IllegalArgumentException("QMP object required");return (Map<String,Object>)value;}
 private static void keys(Map<String,Object> map,String... expected){if(!map.keySet().equals(new HashSet<>(Arrays.asList(expected))))throw new IllegalArgumentException("QMP fields outside supported subset");}
 private static UUID id(Object value){if(!(value instanceof String))throw new IllegalArgumentException("QMP string correlation required");String s=(String)value;UUID id=UUID.fromString(s);if(!id.toString().equals(s))throw new IllegalArgumentException("QMP canonical correlation");return id;}
 private static Message parse(byte[] bytes){
  try {
   Map<String,Object> root=BoundedJson.object(bytes,BoundedJson.QMP);
   if(root.containsKey("QMP")){
    keys(root,"QMP");Map<String,Object> body=object(root.get("QMP"));keys(body,"version","capabilities");Map<String,Object> version=object(body.get("version"));keys(version,"qemu","package");Map<String,Object> qemu=object(version.get("qemu"));keys(qemu,"major","minor","micro");
    for(Object n:qemu.values())if(!(n instanceof Long)||(Long)n<0||(Long)n>1000)throw new IllegalArgumentException("QMP version bound");
    if(!(version.get("package") instanceof String)||!(body.get("capabilities") instanceof List))throw new IllegalArgumentException("QMP greeting types");
    for(Object cap:(List<?>)body.get("capabilities"))if(!(cap instanceof String)||!((String)cap).matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("QMP capability bound");
    return new Message(Kind.GREETING,null,null,body);
   }
   if(root.containsKey("return")){keys(root,"return","id");return new Message(Kind.RETURN,id(root.get("id")),null,object(root.get("return")));}
   if(root.containsKey("error")){keys(root,"error","id");Map<String,Object> error=object(root.get("error"));keys(error,"class","desc");if(!(error.get("class") instanceof String)||!(error.get("desc") instanceof String)||((String)error.get("class")).length()>80||((String)error.get("desc")).length()>256)throw new IllegalArgumentException("QMP error bound");return new Message(Kind.ERROR,id(root.get("id")),null,error);}
   if(root.containsKey("event")){
    if(root.containsKey("timestamp")){keys(root,"event","data","timestamp");Map<String,Object> ts=object(root.get("timestamp"));keys(ts,"seconds","microseconds");for(Object n:ts.values())if(!(n instanceof Long)||(Long)n<0)throw new IllegalArgumentException("QMP timestamp");if((Long)ts.get("microseconds")>=1000000)throw new IllegalArgumentException("QMP microseconds");}else keys(root,"event","data");
    String event=String.valueOf(root.get("event"));if(!Arrays.asList("STOP","RESUME","SHUTDOWN").contains(event))throw new IllegalArgumentException("QMP event outside fixture subset");return new Message(Kind.EVENT,null,event,object(root.get("data")));
   }
   throw new IllegalArgumentException("QMP message outside fixture subset");
  }catch(RuntimeException e){throw new IllegalArgumentException("Invalid bounded QMP transcript",e);}
 }
}
