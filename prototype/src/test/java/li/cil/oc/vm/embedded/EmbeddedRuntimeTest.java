// SPDX-License-Identifier: GPL-2.0-only
package li.cil.oc.vm.embedded;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Isolated fixtures retained beneath the explicitly selected test root. No native process launch. */
public class EmbeddedRuntimeTest {
    interface Work {void run()throws Exception;}
    static void rejects(Work w)throws Exception{try{w.run();fail("Expected rejection");}catch(IOException expected){}}
    static Path fixture()throws IOException{Path root=Paths.get(System.getProperty("ocpp.embedded.fixtureRoot"));return Files.createDirectory(root.resolve("case-"+UUID.randomUUID()));}
    static final EmbeddedRuntime.Admission ALLOW=()->{};
    static final byte[] EXE="inert fixture, not a PE executable".getBytes(StandardCharsets.US_ASCII);
    static Map<String,byte[]> source(){Map<String,byte[]> m=new TreeMap<>();m.put("qemu-system-x86_64.exe",EXE);m.put("COPYING","fixture licence".getBytes(StandardCharsets.US_ASCII));return m;}
    static String raw(Map<String,byte[]> map){StringBuilder s=new StringBuilder("ocpp-private-runtime-v1\nplatform=windows-x86_64\nlicence=unresolved-private-only\n");for(Map.Entry<String,byte[]> e:map.entrySet())s.append(e.getKey()).append('|').append(e.getValue().length).append('|').append(EmbeddedRuntime.digest(e.getValue())).append('\n');return s.toString();}
    static EmbeddedRuntime.Manifest manifest(Map<String,byte[]> m)throws IOException{return parse(raw(m));}
    static EmbeddedRuntime.Manifest parse(String text)throws IOException{byte[] b=text.getBytes(StandardCharsets.UTF_8);return EmbeddedRuntime.Manifest.parse(b,EmbeddedRuntime.digest(b));}
    static EmbeddedRuntime.Installation install(Path root,Map<String,byte[]> m)throws IOException{return EmbeddedRuntime.prepare(root,manifest(m),n->{byte[] b=m.get(n);return b==null?null:new ByteArrayInputStream(b);},ALLOW);}
    @Test public void validManifestHasExactSizes()throws Exception{EmbeddedRuntime.Manifest m=manifest(source());assertEquals(2,m.members.size());assertEquals(EXE.length+15,m.bytes);}
    @Test public void pinnedManifestHashRequired()throws Exception{byte[] b=raw(source()).getBytes(StandardCharsets.US_ASCII);rejects(()->EmbeddedRuntime.Manifest.parse(b,String.join("",Collections.nCopies(64,"0"))));}
    @Test public void manifestSizeBound()throws Exception{rejects(()->EmbeddedRuntime.Manifest.parse(new byte[32769],""));}
    @Test public void rejectTraversalAdsReservedAliasesAndUnicode(){for(String n:new String[]{"../a","a/b","a\\b","C:a","x:stream","CON","con.dll","LPT1.dll","x.","..","a..b","a b","Ã©.dll","/a"})assertFalse(n,EmbeddedRuntime.safeName(n));assertTrue(EmbeddedRuntime.safeName("libstdc++-6.dll"));}
    @Test public void duplicateCaseFoldRejected()throws Exception{rejects(()->parse(raw(source())+"copying|0|"+EmbeddedRuntime.digest(new byte[0])+"\n"));}
    @Test public void wrongProfileRejected()throws Exception{rejects(()->parse(raw(source()).replace("windows-x86_64","linux-aarch64")));}
    @Test public void missingExecutableRejected()throws Exception{Map<String,byte[]> m=source();m.remove("qemu-system-x86_64.exe");rejects(()->manifest(m));}
    @Test public void perMemberAndAggregateBounds()throws Exception{
        String h=EmbeddedRuntime.digest(new byte[0]);String prefix="ocpp-private-runtime-v1\nplatform=windows-x86_64\nlicence=unresolved-private-only\n";
        rejects(()->parse(prefix+"qemu-system-x86_64.exe|33554433|"+h+"\n"));
        rejects(()->parse(prefix+"qemu-system-x86_64.exe|33554432|"+h+"\na.dll|33554432|"+h+"\nb.dll|1|"+h+"\n"));
    }
    @Test public void noTrailingNewlineRejected()throws Exception{String s=raw(source());rejects(()->parse(s.substring(0,s.length()-1)));}
    @Test public void immutableAtomicInstallationAndReuse()throws Exception{Path p=fixture();Map<String,byte[]> m=source();Path path;long modified;
        try(EmbeddedRuntime.Installation i=install(p,m)){i.verify(ALLOW);path=i.directory;assertArrayEquals(EXE,Files.readAllBytes(path.resolve("qemu-system-x86_64.exe")));modified=Files.getLastModifiedTime(path.resolve("COPYING")).toMillis();}
        try(EmbeddedRuntime.Installation i=EmbeddedRuntime.prepare(p,manifest(m),n->{throw new IOException("must reuse, not recopy");},ALLOW)){assertEquals(path,i.directory);assertEquals(modified,Files.getLastModifiedTime(path.resolve("COPYING")).toMillis());}
    }
    @Test public void exclusiveLeaseAndIdempotentClose()throws Exception{Path p=fixture();EmbeddedRuntime.Installation a=install(p,source());try{rejects(()->install(p,source()));}finally{a.close();a.close();}rejects(()->a.verify(ALLOW));try(EmbeddedRuntime.Installation b=install(p,source())){b.verify(ALLOW);}}
    @Test public void wrongHashRetainsPartialWithoutActivation()throws Exception{Path p=fixture();Map<String,byte[]> m=source();rejects(()->EmbeddedRuntime.prepare(p,manifest(m),n->new ByteArrayInputStream(new byte[m.get(n).length]),ALLOW));assertNoActivationWithPartial(p,manifest(m));}
    @Test public void truncatedMemberCannotActivate()throws Exception{Path p=fixture();Map<String,byte[]> m=source();rejects(()->EmbeddedRuntime.prepare(p,manifest(m),n->new ByteArrayInputStream(new byte[0]),ALLOW));assertNoActivationWithPartial(p,manifest(m));}
    @Test public void oversizedMemberCannotActivate()throws Exception{Path p=fixture();Map<String,byte[]> m=source();rejects(()->EmbeddedRuntime.prepare(p,manifest(m),n->new ByteArrayInputStream(new byte[100]),ALLOW));assertNoActivationWithPartial(p,manifest(m));}
    @Test public void missingResourceCannotActivate()throws Exception{Path p=fixture();Map<String,byte[]> m=source();rejects(()->EmbeddedRuntime.prepare(p,manifest(m),n->null,ALLOW));assertNoActivationWithPartial(p,manifest(m));}
    @Test public void failedAdmissionPreventsAnyRuntimeDirectory()throws Exception{Path p=fixture();rejects(()->EmbeddedRuntime.prepare(p,manifest(source()),n->new ByteArrayInputStream(EXE),()->{throw new IOException("guard");}));assertFalse(Files.exists(p.resolve("ocpp-vm")));}
    @Test public void cancelledCopyCannotPublish()throws Exception{Path p=fixture();Map<String,byte[]> m=source();final boolean[] fail={false};rejects(()->EmbeddedRuntime.prepare(p,manifest(m),n->{fail[0]=true;return new ByteArrayInputStream(m.get(n));},()->{if(fail[0])throw new IOException("guard lost");}));assertNoActivationWithPartial(p,manifest(m));}
    @Test public void unexpectedFileInCompletedVersionRejected()throws Exception{Path p=fixture();Path target;try(EmbeddedRuntime.Installation i=install(p,source())){target=i.directory;}Files.write(target.resolve("extra.dll"),EXE,StandardOpenOption.CREATE_NEW);rejects(()->install(p,source()));}
    @Test public void corruptedCompletedFileNeverOverwritten()throws Exception{Path p=fixture();Path target;try(EmbeddedRuntime.Installation i=install(p,source())){target=i.directory;}byte[] bad=new byte[EXE.length];Files.write(target.resolve("qemu-system-x86_64.exe"),bad,StandardOpenOption.WRITE);rejects(()->install(p,source()));assertArrayEquals(bad,Files.readAllBytes(target.resolve("qemu-system-x86_64.exe")));}
    @Test public void fileInsteadOfInstanceRejected()throws Exception{Path p=fixture().resolve("file");Files.write(p,EXE,StandardOpenOption.CREATE_NEW);rejects(()->install(p,source()));}
    @Test public void sourceOnlyPackagedRuntimeIsUnavailable()throws Exception{rejects(()->EmbeddedRuntime.packagedManifest());}
    static void assertNoActivationWithPartial(Path p,EmbeddedRuntime.Manifest m)throws IOException{Path root=p.resolve("ocpp-vm/runtime").resolve(m.hash);assertFalse(Files.exists(root.resolve("windows-x86_64")));try(DirectoryStream<Path> ds=Files.newDirectoryStream(root,"windows-x86_64.partial-*")){assertTrue(ds.iterator().hasNext());}}
}
