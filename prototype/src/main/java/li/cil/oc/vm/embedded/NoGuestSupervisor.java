// SPDX-License-Identifier: GPL-2.0-only
package li.cil.oc.vm.embedded;

import li.cil.oc.vm.json.BoundedJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit developer-only no-guest boundary. Not wired to SuperBIOS START or world loading. */
public final class NoGuestSupervisor {
    public interface Peer extends AutoCloseable {
        String poll()throws IOException; void write(String line)throws IOException; Integer exitCode(); void close()throws IOException;
    }
    public interface Launcher { Peer start(EmbeddedRuntime.Installation installation,List<String> args)throws IOException; }
    public interface Clock { long millis(); void idle()throws InterruptedException; }
    public static final class Result {
        public final boolean prelaunch;public final int exitCode;public final List<String> commands;
        Result(List<String> c,int e){prelaunch=true;exitCode=e;commands=Collections.unmodifiableList(new ArrayList<>(c));}
    }
    public static List<String> arguments(Path data){return Collections.unmodifiableList(Arrays.asList("-machine","none,accel=tcg,dump-guest-core=off","-m","16M","-S","-nodefaults","-no-user-config","-display","none","-monitor","none","-serial","none","-parallel","none","-nic","none","-qmp","stdio","-L",data.toString()));}
    static Result run(EmbeddedRuntime.Installation install,EmbeddedRuntime.Admission admission,Launcher launcher,Clock clock)throws IOException,InterruptedException{
        install.verify(admission);admission.check();long started=clock.millis();List<String> sent=new ArrayList<>();
        try(Peer peer=launcher.start(install,arguments(install.directory.resolve("empty-data")))){
            int phase=0,received=0;long last=started;
            while(true){
                admission.check();long now=clock.millis();if(now<last || now-started>=15000)throw new IOException("Probe deadline/clock");last=now;
                String line=peer.poll();
                if(line!=null){
                    try {
                        byte[] encoded=BoundedJson.utf8Bytes(line,16384,16384);
                        received+=encoded.length;if(received>65536)throw new IOException("QMP input bound");
                        Map<String,Object> message=BoundedJson.object(encoded,BoundedJson.SUPERVISOR);
                        if(phase==0){
                            BoundedJson.keys(message,"QMP");
                            Map<String,Object> greeting=BoundedJson.objectValue(message.get("QMP"));
                            if(greeting.containsKey("capabilities")){
                                BoundedJson.keys(greeting,"version","capabilities");
                                for(Object cap:BoundedJson.array(greeting.get("capabilities")))
                                    if(!BoundedJson.string(cap).matches("[A-Za-z0-9_.-]{1,80}"))throw new IOException("Capability");
                            }else BoundedJson.keys(greeting,"version");
                            Map<String,Object> version=BoundedJson.objectValue(greeting.get("version"));
                            BoundedJson.keys(version,"qemu","package");BoundedJson.string(version.get("package"));
                            Map<String,Object> q=BoundedJson.objectValue(version.get("qemu"));BoundedJson.keys(q,"major","minor","micro");
                            if(BoundedJson.integer(q.get("major"))!=4||BoundedJson.integer(q.get("minor"))!=0||BoundedJson.integer(q.get("micro"))!=92)throw new IOException("Runtime version");
                            send(peer,sent,"qmp_capabilities","cap");phase=1;
                        }else if(message.containsKey("event")){
                            if(message.containsKey("timestamp")){
                                BoundedJson.keys(message,"event","data","timestamp");
                                Map<String,Object> ts=BoundedJson.objectValue(message.get("timestamp"));BoundedJson.keys(ts,"seconds","microseconds");
                                long seconds=BoundedJson.integer(ts.get("seconds")),micro=BoundedJson.integer(ts.get("microseconds"));
                                if(seconds<0||micro<0||micro>=1000000)throw new IOException("Timestamp");
                            }else BoundedJson.keys(message,"event","data");
                            BoundedJson.objectValue(message.get("data"));
                            if(phase!=4||!"SHUTDOWN".equals(BoundedJson.string(message.get("event"))))throw new IOException("Unexpected QMP event");
                        }else{
                            BoundedJson.keys(message,"return","id");
                            String expected=phase==1?"cap":phase==4?"quit":"status";
                            if(!expected.equals(BoundedJson.string(message.get("id"))))throw new IOException("QMP correlation/error");
                            Map<String,Object> reply=BoundedJson.objectValue(message.get("return"));
                            if(phase==1){
                                if(!reply.isEmpty())throw new IOException("Capabilities acknowledgement");
                                send(peer,sent,"query-status","status");phase=2;
                            }else if(phase==4){
                                if(!reply.isEmpty())throw new IOException("Quit acknowledgement");
                            }else{
                                if(reply.containsKey("singlestep")){
                                    BoundedJson.keys(reply,"status","running","singlestep");BoundedJson.bool(reply.get("singlestep"));
                                }else BoundedJson.keys(reply,"status","running");
                                if(BoundedJson.bool(reply.get("running"))||!"prelaunch".equals(BoundedJson.string(reply.get("status"))))throw new IOException("Unexpected execution state");
                                send(peer,sent,"quit","quit");phase=4;
                            }
                        }
                    }catch(RuntimeException malformed){throw new IOException("Invalid bounded QMP",malformed);}
                }
                Integer exit=peer.exitCode();if(exit!=null){if(phase==4&&exit==0)return new Result(sent,exit);throw new IOException("Exit before successful bounded probe");}
                clock.idle();
            }
        }
    }
    private static void send(Peer p,List<String> sent,String operation,String id)throws IOException{p.write("{\"execute\":\""+operation+"\",\"id\":\""+id+"\"}\r\n");sent.add(operation);}
    /** Future explicit callers must also admit host resources. No untrusted paths, arguments or environment accepted. */
    public static final class ProcessLauncher implements Launcher {
        public Peer start(EmbeddedRuntime.Installation install,List<String> args)throws IOException{
            if(!System.getProperty("os.name").startsWith("Windows")||!System.getProperty("os.arch").matches("(?i)(amd64|x86_64)"))throw new IOException("Unqualified host platform");
            Path data=install.directory.resolve("empty-data");
            // Extraction is immutable and has no firmware. Do not manufacture additional files in it.
            if(Files.exists(data,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unexpected data directory");
            if(!args.equals(arguments(data)))throw new IOException("Non-allowlisted process arguments");
            install.verify(()->{});
            List<String> command=new ArrayList<>();command.add(install.directory.resolve("qemu-system-x86_64.exe").toString());command.addAll(args);
            ProcessBuilder builder=new ProcessBuilder(command).directory(install.directory.toFile());Map<String,String> env=builder.environment();
            String systemRoot=env.get("SystemRoot");if(systemRoot==null)throw new IOException("SystemRoot missing");env.clear();env.put("SystemRoot",systemRoot);env.put("PATH",install.directory+";"+systemRoot+"\\System32");env.put("QEMU_AUDIO_DRV","none");env.put("QEMU_MODULE_DIR",data.toString());env.put("QEMU_DATA_DIR",data.toString());
            return new ProcessPeer(builder.start());
        }
    }
    static final class ProcessPeer implements Peer {
        private final Process process;private final List<String> lines=new ArrayList<>();private volatile IOException error;private int stdoutBytes,stderrBytes;private boolean closed;
        private final Thread out,err;
        ProcessPeer(Process p){process=p;out=reader(p.getInputStream(),false);err=reader(p.getErrorStream(),true);}
        Thread reader(final InputStream stream,final boolean stderr){Thread t=new Thread(()->{
            try{ByteArrayOutputStream line=new ByteArrayOutputStream();int b;while((b=stream.read())!=-1){synchronized(this){if(stderr){if(++stderrBytes>16384)throw new IOException("stderr bound");}else if(++stdoutBytes>65536)throw new IOException("stdout bound");}
                if(stderr)continue;if(b=='\n'){synchronized(this){if(lines.size()>=32)throw new IOException("QMP queue bound");try{lines.add(BoundedJson.utf8(line.toByteArray(),16384));}catch(IllegalArgumentException malformed){throw new IOException("Invalid QMP UTF-8",malformed);}}line.reset();}else{if(line.size()>=16384)throw new IOException("QMP line bound");line.write(b);}}
                if(!stderr&&line.size()>0)throw new IOException("Truncated QMP line");
            }catch(IOException e){error=e;}
        },stderr?"ocpp-private-qmp-stderr":"ocpp-private-qmp-stdout");t.setDaemon(true);t.start();return t;}
        public synchronized String poll()throws IOException{if(error!=null)throw error;return lines.isEmpty()?null:lines.remove(0);}
        public void write(String s)throws IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);if(b.length>256)throw new IOException("Control bound");process.getOutputStream().write(b);process.getOutputStream().flush();}
        public Integer exitCode(){try{return process.exitValue();}catch(IllegalThreadStateException e){return null;}}
        public void close()throws IOException{
            if(closed)return;closed=true;
            try{process.getOutputStream().close();if(process.isAlive()){process.destroy();if(!process.waitFor(1000,TimeUnit.MILLISECONDS)){process.destroyForcibly();if(!process.waitFor(1000,TimeUnit.MILLISECONDS))throw new IOException("Owned child exit uncertain");}}}
            catch(InterruptedException e){process.destroyForcibly();Thread.currentThread().interrupt();throw new IOException("Interrupted cleanup",e);}
            finally{process.getInputStream().close();process.getErrorStream().close();}
        }
    }
    private NoGuestSupervisor(){}
}
