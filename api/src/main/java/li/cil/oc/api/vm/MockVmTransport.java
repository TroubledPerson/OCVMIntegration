package li.cil.oc.api.vm;

import java.util.*;

/** Synchronous bounded in-memory protocol fixture; never QMP passthrough. */
public final class MockVmTransport {
    private final UUID world,machine,owner;
    private final Set<UUID> seen=new HashSet<>();
    private boolean revoked;
    public MockVmTransport(UUID world,UUID machine,UUID owner){this.world=world;this.machine=machine;this.owner=owner;}
    public synchronized String request(UUID w,UUID c,UUID p,UUID nonce,String operation,String payload){
        if(revoked||!world.equals(w)||!machine.equals(c)||!owner.equals(p))throw new SecurityException("VM capability denied");
        if(payload==null||payload.length()>65536||seen.size()>=64||!seen.add(Objects.requireNonNull(nonce)))throw new IllegalArgumentException("request/replay limit");
        if("status".equals(operation))return "offline; no QEMU runtime or guest execution";
        if("validate-manifest".equals(operation)){VmManifest.Plan plan=VmManifest.validate(payload);if(!world.equals(plan.worldId)||!machine.equals(plan.caseId)||!owner.equals(plan.ownerId))throw new SecurityException("manifest identity mismatch");return "valid dry-run aggregate; runtime disabled";}
        throw new SecurityException("operation unavailable");
    }
    public synchronized void revoke(){revoked=true;}
}
