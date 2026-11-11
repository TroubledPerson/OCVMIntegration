// SPDX-License-Identifier: GPL-2.0-only
package li.cil.oc.vm.embedded;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Private prototype. Explicit preparation only; neither discovery nor registration executes code. */
public final class EmbeddedRuntime {
    public static final long MAX_BYTES=64L*1024*1024;
    public static final String RESOURCE_ROOT="META-INF/ocpp-qemu/windows-x86_64/";
    public interface Resources { InputStream open(String name) throws IOException; }
    public interface Admission { void check() throws IOException; }
    public static final class Member {
        public final String name,sha256; public final long size;
        Member(String n,long z,String h){name=n;size=z;sha256=h;}
    }
    public static final class Manifest {
        public final String hash; public final List<Member> members; public final long bytes;
        private Manifest(String h,List<Member> m,long b){hash=h;members=Collections.unmodifiableList(m);bytes=b;}
        public static Manifest parse(byte[] raw,String expected) throws IOException {
            if(raw.length>32768 || !digest(raw).equals(expected))throw new IOException("Manifest identity");
            String text=new String(raw,StandardCharsets.US_ASCII);
            if(!Arrays.equals(raw,text.getBytes(StandardCharsets.US_ASCII)))throw new IOException("Manifest encoding");
            String[] lines=text.split("\n",-1);
            if(lines.length<5 || !lines[0].equals("ocpp-private-runtime-v1") || !lines[1].equals("platform=windows-x86_64") || !lines[2].equals("licence=unresolved-private-only") || !lines[lines.length-1].isEmpty())throw new IOException("Manifest profile");
            Set<String> names=new HashSet<>();List<Member> result=new ArrayList<>();long total=0;
            for(int i=3;i<lines.length-1;i++){
                String[] p=lines[i].split("\\|",-1);
                if(p.length!=3 || !safeName(p[0]) || !p[1].matches("[0-9]{1,9}") || !p[2].matches("[a-f0-9]{64}") || !names.add(p[0].toLowerCase(Locale.ROOT)))throw new IOException("Manifest member");
                long size=Long.parseLong(p[1]);
                if(size>32L*1024*1024 || total>MAX_BYTES-size || result.size()>=64)throw new IOException("Expanded size/member limit");
                total+=size;result.add(new Member(p[0],size,p[2]));
            }
            if(!names.contains("qemu-system-x86_64.exe") || result.isEmpty())throw new IOException("Missing executable");
            return new Manifest(expected,result,total);
        }
    }
    public static boolean safeName(String n){
        if(n==null || !n.matches("[A-Za-z0-9_+-][A-Za-z0-9_.+-]{0,127}") || n.endsWith(".") || n.contains(".."))return false;
        return !n.split("\\.",2)[0].matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])");
    }
    public static String digest(byte[] b){MessageDigest d=sha();return hex(d.digest(b));}
    static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    static byte[] bounded(InputStream in,int limit) throws IOException {
        if(in==null)throw new IOException("Missing resource");
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=stream.read(b))!=-1){if(n==0)continue;if(out.size()>limit-n)throw new IOException("Resource limit");out.write(b,0,n);}return out.toByteArray();
        }
    }
    /** Reject existing symlink/junction aliases. Private instance roots must be owner-controlled. */
    static void checkPath(Path path) throws IOException {
        Path p=path.toAbsolutePath().normalize();
        for(Path a=p;a!=null;a=a.getParent())if(Files.exists(a,LinkOption.NOFOLLOW_LINKS)){
            BasicFileAttributes at=Files.readAttributes(a,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(at.isSymbolicLink() || at.isOther() || !a.toRealPath().equals(a))throw new IOException("Link/reparse/alias rejected: "+a);
        }
    }
    static void directory(Path p,Admission admission)throws IOException{
        admission.check();checkPath(p);
        if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)){
            if(p.getParent()==null)throw new IOException("Missing filesystem root");directory(p.getParent(),admission);Files.createDirectory(p);
        }
        if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Not a directory");checkPath(p);
    }
    public static final class Installation implements AutoCloseable {
        public final Path directory; public final Manifest manifest;
        private final FileChannel channel; private final FileLock lock; private boolean closed;
        private Installation(Path p,Manifest m,FileChannel c,FileLock l){directory=p;manifest=m;channel=c;lock=l;}
        public synchronized void verify(Admission admission)throws IOException{if(closed || !lock.isValid())throw new IOException("Lease closed");verifyDirectory(directory,manifest,admission);}
        public synchronized void close()throws IOException{if(closed)return;closed=true;try{lock.release();}finally{channel.close();}}
    }
    public static Manifest packagedManifest()throws IOException{
        throw new IOException("No qualified runtime is bundled in this source-only project");
    }
    public static Installation prepare(Path instance,Admission admission)throws IOException{
        return prepare(instance,packagedManifest(),n->EmbeddedRuntime.class.getResourceAsStream("/"+RESOURCE_ROOT+n),admission);
    }
    static Installation prepare(Path instance,Manifest manifest,Resources resources,Admission admission)throws IOException{
        Objects.requireNonNull(admission);admission.check();Path base=instance.toAbsolutePath().normalize();checkPath(base);
        if(!Files.isDirectory(base,LinkOption.NOFOLLOW_LINKS))throw new IOException("Choose an existing owned instance directory");
        Path root=base.resolve("ocpp-vm/runtime");directory(root,admission);
        Path lockPath=root.resolve(manifest.hash+".lock");checkPath(lockPath);admission.check();
        FileChannel channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);FileLock lock=null;
        try{
            try{lock=channel.tryLock();}catch(OverlappingFileLockException e){throw new IOException("Runtime lease busy",e);}
            if(lock==null)throw new IOException("Runtime lease busy");
            Path version=root.resolve(manifest.hash);Path target=version.resolve("windows-x86_64");directory(version,admission);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){verifyDirectory(target,manifest,admission);return new Installation(target,manifest,channel,lock);}
            Path partial=version.resolve("windows-x86_64.partial-"+UUID.randomUUID());admission.check();Files.createDirectory(partial);
            byte[] buffer=new byte[65536];
            for(Member member:manifest.members){
                admission.check();checkPath(partial);Path dest=partial.resolve(member.name);MessageDigest hash=sha();long size=0;
                InputStream source=resources.open(member.name);if(source==null)throw new IOException("Missing member "+member.name);
                try(InputStream in=source;OutputStream out=Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                    int n;while((n=in.read(buffer))!=-1){if(n==0)continue;if(size>member.size-n)throw new IOException("Oversized member");admission.check();out.write(buffer,0,n);hash.update(buffer,0,n);size+=n;}
                }
                if(size!=member.size || !hex(hash.digest()).equals(member.sha256))throw new IOException("Member identity "+member.name);
            }
            verifyDirectory(partial,manifest,admission);admission.check();checkPath(version);
            Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE); // Fail without fallback or deletion if atomic activation is unavailable.
            verifyDirectory(target,manifest,admission);return new Installation(target,manifest,channel,lock);
        }catch(IOException|RuntimeException e){if(lock!=null)lock.release();channel.close();throw e;}
    }
    static void verifyDirectory(Path directory,Manifest manifest,Admission admission)throws IOException{
        admission.check();checkPath(directory);if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Runtime directory missing");
        Set<String> expected=new HashSet<>();for(Member member:manifest.members)expected.add(member.name);
        try(DirectoryStream<Path> stream=Files.newDirectoryStream(directory)){for(Path p:stream)if(!expected.remove(p.getFileName().toString()))throw new IOException("Unlisted member");}
        if(!expected.isEmpty())throw new IOException("Incomplete installation");
        byte[] b=new byte[65536];
        for(Member member:manifest.members){Path p=directory.resolve(member.name);checkPath(p);if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)!=member.size)throw new IOException("Member size/type");MessageDigest h=sha();long total=0;
            try(InputStream in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){int n;while((n=in.read(b))!=-1){if(n==0)continue;admission.check();total+=n;if(total>member.size)throw new IOException("Member changed during verification");h.update(b,0,n);}}
            if(total!=member.size||!hex(h.digest()).equals(member.sha256))throw new IOException("Member hash");
        }
    }
    private EmbeddedRuntime(){}
}
