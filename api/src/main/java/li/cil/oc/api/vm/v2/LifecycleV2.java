package li.cil.oc.api.vm.v2;

import li.cil.oc.api.vm.v2.ProtocolV2.Error;

/** Explicitly simulated state model. Production adapter always reports RUNTIME_DISABLED. */
public final class LifecycleV2 {
    public enum State { OFFLINE_ONLY, STOPPED, STARTING, RUNNING, STOPPING, FAILED, REVOKED }
    public final boolean simulated;private State state;private boolean quarantined;
    private LifecycleV2(boolean simulated){this.simulated=simulated;state=simulated?State.STOPPED:State.OFFLINE_ONLY;}
    public static LifecycleV2 productionUnavailable(){return new LifecycleV2(false);}
    public static LifecycleV2 syntheticFixture(){return new LifecycleV2(true);}
    public synchronized Error start(){if(!simulated)return Error.RUNTIME_DISABLED;if(state!=State.STOPPED||quarantined)return state==State.REVOKED?Error.REVOKED:Error.FAILED;state=State.STARTING;return Error.NONE;}
    public synchronized Error stop(){if(!simulated)return Error.RUNTIME_DISABLED;if(state==State.STOPPED||state==State.STOPPING)return Error.NONE;if(state!=State.STARTING&&state!=State.RUNNING)return Error.FAILED;state=State.STOPPING;return Error.NONE;}
    public synchronized void observedRunning(){if(!simulated||state!=State.STARTING)throw new IllegalStateException("invalid simulated transition");state=State.RUNNING;}
    public synchronized void observedCleanStop(){if(!simulated||state!=State.STOPPING)throw new IllegalStateException("clean stop not observed");state=State.STOPPED;}
    public synchronized void observedCrash(){if(!simulated||(state!=State.STARTING&&state!=State.RUNNING&&state!=State.STOPPING))throw new IllegalStateException("invalid crash transition");state=State.FAILED;quarantined=true;}
    public synchronized void revoke(){state=State.REVOKED;}
    public synchronized State state(){return state;}
    public synchronized boolean requiresDiskReconciliation(){return quarantined;}
    public boolean mayExecuteGuest(){return false;}
    public boolean deletesData(){return false;}
}
