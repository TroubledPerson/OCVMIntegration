// SPDX-License-Identifier: GPL-2.0-only
package li.cil.oc.vm.embedded;

import org.junit.Test;import static org.junit.Assert.*;
import java.io.*;import java.nio.file.*;import java.util.*;

/** Exercises the production supervisor with memory peers. ProcessLauncher is never called. */
public class NoGuestSupervisorTest {
    static final String GREETING="{\"QMP\":{\"version\":{\"qemu\":{\"major\":4,\"minor\":0,\"micro\":92},\"package\":\"synthetic-fixture\"}}}";
    static final String STATUS="{\"return\":{\"status\":\"prelaunch\",\"running\":false},\"id\":\"status\"}";
    static class MemoryPeer implements NoGuestSupervisor.Peer {
        final ArrayDeque<String> q=new ArrayDeque<>();final List<String> writes=new ArrayList<>();boolean closed,quit,ack=true,exit=true;String status=STATUS;
        MemoryPeer(){q.add(GREETING);}
        public String poll(){return q.poll();}
        public void write(String s){writes.add(s);if(s.contains("qmp_capabilities"))q.add("{\"return\":{},\"id\":\"cap\"}");else if(s.contains("query-status"))q.add(status);else if(s.contains("quit")){quit=true;if(ack)q.add("{\"return\":{},\"id\":\"quit\"}");}}
        public Integer exitCode(){return exit&&quit&&q.isEmpty()?0:null;}
        public void close(){closed=true;}
    }
    static class Tick implements NoGuestSupervisor.Clock {long time;public long millis(){return time;}public void idle(){time+=5;}}
    static NoGuestSupervisor.Result run(MemoryPeer peer,NoGuestSupervisor.Clock clock)throws Exception{
        Path p=EmbeddedRuntimeTest.fixture();try(EmbeddedRuntime.Installation i=EmbeddedRuntimeTest.install(p,EmbeddedRuntimeTest.source())){
            return NoGuestSupervisor.run(i,EmbeddedRuntimeTest.ALLOW,(installation,args)->{assertEquals(NoGuestSupervisor.arguments(i.directory.resolve("empty-data")),args);assertEquals(i,installation);return peer;},clock);
        }
    }
    @Test public void capabilityStatusQuitOnlyAndObservedExit()throws Exception{MemoryPeer p=new MemoryPeer();NoGuestSupervisor.Result r=run(p,new Tick());assertTrue(r.prelaunch);assertEquals(0,r.exitCode);assertEquals(Arrays.asList("qmp_capabilities","query-status","quit"),r.commands);assertTrue(p.closed);assertEquals(3,p.writes.size());for(String s:p.writes)assertFalse(s.contains("cont"));}
    @Test public void quitResponseNotRequiredButExitIs()throws Exception{MemoryPeer p=new MemoryPeer();p.ack=false;assertEquals(0,run(p,new Tick()).exitCode);assertTrue(p.closed);}
    @Test public void acknowledgementWithoutExitTimesOut()throws Exception{MemoryPeer p=new MemoryPeer();p.exit=false;EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));assertTrue(p.closed);}
    @Test public void runningStateNeverAccepted()throws Exception{MemoryPeer p=new MemoryPeer();p.status=STATUS.replace("false","true");EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));assertTrue(p.closed);assertFalse(p.quit);}
    @Test public void stoppedInsteadOfPrelaunchRejected()throws Exception{MemoryPeer p=new MemoryPeer();p.status=STATUS.replace("prelaunch","paused");EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));}
    @Test public void mismatchedCorrelationRejected()throws Exception{MemoryPeer p=new MemoryPeer();p.status=STATUS.replace("\"status\"}","\"wrong\"}");EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));}
    @Test public void qmpErrorRejected()throws Exception{MemoryPeer p=new MemoryPeer();p.status="{\"error\":{\"class\":\"fixture\"},\"id\":\"status\"}";EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));}
    @Test public void wrongVersionRejectedBeforeNegotiation()throws Exception{MemoryPeer p=new MemoryPeer();p.q.clear();p.q.add(GREETING.replace("\"micro\":92","\"micro\":0"));EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));assertTrue(p.writes.isEmpty());}
    @Test public void malformedGreetingRejected()throws Exception{MemoryPeer p=new MemoryPeer();p.q.clear();p.q.add("not json");EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));}
    @Test public void noGreetingHasDeadline()throws Exception{MemoryPeer p=new MemoryPeer();p.q.clear();EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));assertTrue(p.closed);}
    @Test public void oversizedLineRejected()throws Exception{MemoryPeer p=new MemoryPeer();p.q.clear();p.q.add(String.join("",Collections.nCopies(16385,"a")));EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));}
    @Test public void duplicateGreetingFailsClosed()throws Exception{MemoryPeer p=new MemoryPeer();p.q.add(GREETING);EmbeddedRuntimeTest.rejects(()->run(p,new Tick()));assertTrue(p.closed);}
    @Test public void backwardsClockFailsClosed()throws Exception{MemoryPeer p=new MemoryPeer();Tick t=new Tick(){public void idle(){time--;}};EmbeddedRuntimeTest.rejects(()->run(p,t));assertTrue(p.closed);}
    @Test public void noGuestArgumentsHaveNoDeviceOrDiskInputs(){List<String> a=NoGuestSupervisor.arguments(Paths.get("owned-empty-data"));assertTrue(a.contains("-S"));assertTrue(a.contains("none,accel=tcg,dump-guest-core=off"));assertEquals("none",a.get(a.indexOf("-nic")+1));for(String n:new String[]{"-drive","-blockdev","-cdrom","-kernel","-bios","-netdev","-device","-incoming"})assertFalse(a.contains(n));}
    @Test public void guardLossClosesPeerAndSendsNoStart()throws Exception{Path p=EmbeddedRuntimeTest.fixture();try(EmbeddedRuntime.Installation i=EmbeddedRuntimeTest.install(p,EmbeddedRuntimeTest.source())){MemoryPeer peer=new MemoryPeer();final boolean[] lost={false};EmbeddedRuntimeTest.rejects(()->NoGuestSupervisor.run(i,()->{if(lost[0])throw new IOException("guard lost");},(x,a)->{lost[0]=true;return peer;},new Tick()));assertTrue(peer.closed);assertTrue(peer.writes.isEmpty());}}
}
