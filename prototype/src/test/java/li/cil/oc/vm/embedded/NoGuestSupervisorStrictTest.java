// SPDX-License-Identifier: GPL-2.0-only
package li.cil.oc.vm.embedded;

import org.junit.Test;

public class NoGuestSupervisorStrictTest {
    @Test public void duplicateAndEscapedEquivalentFieldsAreRejected()throws Exception{
        NoGuestSupervisorTest.MemoryPeer p=new NoGuestSupervisorTest.MemoryPeer();
        p.q.clear();p.q.add(NoGuestSupervisorTest.GREETING.replace("\"major\":4","\"major\":4,\"\\u006dajor\":4"));
        EmbeddedRuntimeTest.rejects(()->NoGuestSupervisorTest.run(p,new NoGuestSupervisorTest.Tick()));
    }
    @Test public void coerciveNumericStringsDecimalAndExponentAreRejected()throws Exception{
        for(String n:new String[]{"\"4\"","4.0","4e0","04","9223372036854775808"}){
            NoGuestSupervisorTest.MemoryPeer p=new NoGuestSupervisorTest.MemoryPeer();
            p.q.clear();p.q.add(NoGuestSupervisorTest.GREETING.replace("\"major\":4","\"major\":"+n));
            EmbeddedRuntimeTest.rejects(()->NoGuestSupervisorTest.run(p,new NoGuestSupervisorTest.Tick()));
        }
    }
    @Test public void unknownTrailingAndCoerciveStatusFieldsAreRejected()throws Exception{
        for(String status:new String[]{
            NoGuestSupervisorTest.STATUS+" {}",
            NoGuestSupervisorTest.STATUS.replace("\"running\":false","\"running\":\"false\""),
            NoGuestSupervisorTest.STATUS.replace("\"running\":false","\"running\":false,\"extra\":0"),
            NoGuestSupervisorTest.STATUS.replace("\"id\":\"status\"","\"id\":\"status\",\"extra\":0")}){
            NoGuestSupervisorTest.MemoryPeer p=new NoGuestSupervisorTest.MemoryPeer();p.status=status;
            EmbeddedRuntimeTest.rejects(()->NoGuestSupervisorTest.run(p,new NoGuestSupervisorTest.Tick()));
        }
    }
    @Test public void depthAndOversizedTokensRejectWithoutLaunchingAnything()throws Exception{
        String nested="0";for(int i=0;i<10;i++)nested="["+nested+"]";
        final String payload=nested;
        NoGuestSupervisorTest.MemoryPeer p=new NoGuestSupervisorTest.MemoryPeer();p.q.clear();
        p.q.add("{\"extra\":"+payload+"}");
        EmbeddedRuntimeTest.rejects(()->NoGuestSupervisorTest.run(p,new NoGuestSupervisorTest.Tick()));
        NoGuestSupervisorTest.MemoryPeer big=new NoGuestSupervisorTest.MemoryPeer();big.q.clear();
        big.q.add(NoGuestSupervisorTest.GREETING.replace("synthetic-fixture",String.join("",java.util.Collections.nCopies(4097,"a"))));
        EmbeddedRuntimeTest.rejects(()->NoGuestSupervisorTest.run(big,new NoGuestSupervisorTest.Tick()));
    }
}
