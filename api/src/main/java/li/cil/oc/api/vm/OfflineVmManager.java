package li.cil.oc.api.vm;
import java.util.*;

/** Bounded, volatile, per-owner catalogue. No path discovery, disk I/O or import. */
public final class OfflineVmManager {
    public static final String RELATIVE_RUNTIME="ocpp-vm/runtime", RELATIVE_LIBRARY="ocpp-vm/library";
    private final UUID world,machine,owner;
    private final Map<UUID,VmManifest.Plan> packages=new LinkedHashMap<>();
    private boolean revoked;
    public OfflineVmManager(UUID world,UUID machine,UUID owner){this.world=Objects.requireNonNull(world);this.machine=Objects.requireNonNull(machine);this.owner=Objects.requireNonNull(owner);}
    private void authorize(UUID caller){if(revoked||!owner.equals(caller))throw new SecurityException("manager capability revoked or denied");}
    public synchronized void stage(UUID caller,String manifest){
        authorize(caller);VmManifest.Plan p=VmManifest.validate(manifest);
        if(!world.equals(p.worldId)||!machine.equals(p.caseId)||!owner.equals(p.ownerId))throw new SecurityException("package ownership mismatch");
        if(packages.size()>=16||packages.containsKey(p.packageId))throw new IllegalStateException("catalogue limit or duplicate package");
        for(VmManifest.Plan old:packages.values())if(old.volumeId.equals(p.volumeId)||old.nvramId.equals(p.nvramId))throw new IllegalStateException("duplicate disk/NVRAM identity");
        packages.put(p.packageId,p);
    }
    public synchronized List<UUID> list(UUID caller){authorize(caller);return Collections.unmodifiableList(new ArrayList<>(packages.keySet()));}
    public synchronized void revoke(){revoked=true;}
    public boolean mayLaunch(){return false;}
    public String supportStatus(){return "metadata-only; every host/guest runtime combination untested";}
}
