package li.cil.oc.api.vm.v2;

import java.util.*;
import li.cil.oc.api.vm.v2.ProtocolV2.*;

/** Authority must come from the server integration, never from packet-supplied identity fields. */
public final class SessionV2 {
    public interface Authorizer { boolean allowed(Identity authoritativeActor); }
    public static final class Pool {
        private final Thread dispatcher=Thread.currentThread();
        private final int maximum;private final Map<UUID,SessionV2> sessions=new LinkedHashMap<>();
        public Pool(int maximum){if(maximum<1||maximum>32)throw new IllegalArgumentException("server session ceiling");this.maximum=maximum;}
        public synchronized SessionV2 open(Identity identity,Authorizer authorizer,long generation,long epoch,String canvas,long now){
            if(dispatcher!=Thread.currentThread())throw new IllegalStateException("authoritative dispatcher required");
            tick(now);int count=0;for(SessionV2 s:sessions.values())if(s.identity.caseKey().equals(identity.caseKey()))count++;
            if(sessions.size()>=maximum||count>=2)throw new IllegalStateException("session quota");
            SessionV2 s=new SessionV2(identity,authorizer,generation,epoch,canvas,now);sessions.put(s.id,s);return s;
        }
        /** Caller drives this from authoritative ticks; no background thread/listener is created. */
        public synchronized void tick(long now){if(dispatcher!=Thread.currentThread())throw new IllegalStateException("authoritative dispatcher required");Iterator<SessionV2> it=sessions.values().iterator();while(it.hasNext()){SessionV2 s=it.next();s.expire(now);if(s.revoked)it.remove();}}
        public synchronized int size(){return sessions.size();}
    }
    public final UUID id=UUID.randomUUID();public final Identity identity;public final long generation,epoch;public final String canvas;
    private final Authorizer authorizer;private long time,lastSequence;private boolean revoked;
    private final Thread dispatcher=Thread.currentThread();
    private final Map<UUID,Long> pending=new LinkedHashMap<>();private final List<Runnable> revocations=new ArrayList<>();
    private SessionV2(Identity identity,Authorizer authorizer,long generation,long epoch,String canvas,long now){
        this.identity=Objects.requireNonNull(identity);this.authorizer=Objects.requireNonNull(authorizer);if(generation<1||epoch<0||now<0||!authorizer.allowed(identity))throw new SecurityException("session admission denied");
        this.generation=generation;this.epoch=epoch;this.canvas=ProtocolV2.hash(canvas);time=now;
    }
    /** Transport adapters must marshal onto the constructing authoritative dispatcher. */
    private void dispatcher(){if(Thread.currentThread()!=dispatcher)throw new IllegalStateException("authoritative dispatcher required");}
    public void check(Identity actor,UUID session,long generation,long epoch,String canvas,long now){
        dispatcher();
        expire(now);if(revoked||!identity.equals(actor)||!authorizer.allowed(actor)){revoke();throw new SecurityException("session denied");}
        if(!id.equals(session)||this.generation!=generation||this.epoch!=epoch||!this.canvas.equals(canvas))throw new SecurityException("stale session/layout");
        time=now;
    }
    public void accept(Identity actor,Message m,long now){
        check(actor,m.session,m.generation,m.epoch,m.canvas,now);
        if(!identity.equals(m.identity)||lastSequence==Long.MAX_VALUE||m.sequence!=lastSequence+1)throw new SecurityException("identity/sequence denied");
        Iterator<Long> deadlines=pending.values().iterator();while(deadlines.hasNext())if(deadlines.next()<=now)deadlines.remove();
        if(pending.size()>=32||pending.containsKey(m.correlation))throw new IllegalStateException("pending request quota/replay");
        pending.put(m.correlation,deadline(now,2000));lastSequence=m.sequence;
    }
    public void complete(UUID correlation){dispatcher();if(pending.remove(correlation)==null)throw new IllegalArgumentException("unknown correlation");}
    public int pending(){dispatcher();return pending.size();}
    public void onRevoke(Runnable callback){dispatcher();Objects.requireNonNull(callback);if(revoked){callback.run();return;}if(revocations.size()>=3)throw new IllegalStateException("revocation hook quota");revocations.add(callback);}
    public void expire(long now){dispatcher();if(now<0||now<time||now-time>=30000)revoke();}
    public void revoke(){dispatcher();if(revoked)return;revoked=true;pending.clear();RuntimeException failure=null;for(Runnable callback:revocations){try{callback.run();}catch(RuntimeException e){if(failure==null)failure=e;}}revocations.clear();if(failure!=null)throw failure;}
    public boolean revoked(){dispatcher();return revoked;}
    static long deadline(long now,long duration){return now>Long.MAX_VALUE-duration?Long.MAX_VALUE:now+duration;}
}
