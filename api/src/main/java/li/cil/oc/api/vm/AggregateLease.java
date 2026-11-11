package li.cil.oc.api.vm;

import java.util.*;

/** One aggregate image identity. Removal never deletes data or hot-detaches it. */
public final class AggregateLease {
    private static final Map<String,UUID> CLAIMS=new java.util.concurrent.ConcurrentHashMap<>();
    public enum State { OFF, RUNNING, PAUSED, SAVED, PENDING_DETACH, DETACHED }
    public final UUID world,raid,volume,owner;
    private UUID claimedCase;
    private State state=State.OFF;
    public AggregateLease(UUID world,UUID raid,UUID volume,UUID owner){this.world=Objects.requireNonNull(world);this.raid=Objects.requireNonNull(raid);this.volume=Objects.requireNonNull(volume);this.owner=Objects.requireNonNull(owner);}
    public synchronized void acquire(UUID world,UUID machine,UUID caller){
        if(!this.world.equals(world)||!owner.equals(caller)||state!=State.OFF||claimedCase!=null)throw new IllegalStateException("aggregate lease denied");
        Objects.requireNonNull(machine);
        if(CLAIMS.putIfAbsent(world+"/"+volume,machine)!=null)throw new IllegalStateException("aggregate already claimed");
        claimedCase=machine;
    }
    public synchronized void observe(State next){if(claimedCase==null)throw new IllegalStateException("unclaimed volume");if(next==State.DETACHED)throw new IllegalArgumentException("use powered-off detach");state=next;}
    public synchronized void removed(){state=state==State.OFF?State.DETACHED:State.PENDING_DETACH;if(state==State.DETACHED)release();}
    public synchronized void detachAfterConfirmedOff(){if(state!=State.OFF)throw new IllegalStateException("guest must be fully off");state=State.DETACHED;release();}
    private void release(){if(claimedCase!=null)CLAIMS.remove(world+"/"+volume,claimedCase);claimedCase=null;}
    public synchronized State state(){return state;}
    public synchronized boolean imageRetained(){return true;}
    public synchronized String identity(){return world+"/"+raid+"/"+volume;}
}
