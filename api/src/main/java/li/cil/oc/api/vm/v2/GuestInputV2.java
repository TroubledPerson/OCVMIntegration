package li.cil.oc.api.vm.v2;

import java.util.*;
import li.cil.oc.api.vm.v2.ProtocolV2.Identity;

/** Guest-directed synthetic input only, not OS input injection. Integration must drive focus/expiry. */
public final class GuestInputV2 implements AutoCloseable {
    public enum Kind { KEY_DOWN,KEY_UP,POINTER,BUTTON_DOWN,BUTTON_UP,RELEASE_ALL }
    public static final class Event {
        public final Kind kind;public final int code,x,y;
        private Event(Kind kind,int code,int x,int y){this.kind=kind;this.code=code;this.x=x;this.y=y;}
        public static Event key(boolean down,int code){if(code<0||code>255)throw new IllegalArgumentException("guest key");return new Event(down?Kind.KEY_DOWN:Kind.KEY_UP,code,0,0);}
        public static Event button(boolean down,int code){if(code<0||code>7)throw new IllegalArgumentException("guest button");return new Event(down?Kind.BUTTON_DOWN:Kind.BUTTON_UP,code,0,0);}
        public static Event pointer(double x,double y,int width,int height){if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1||width<1||width>640||height<1||height>480)throw new IllegalArgumentException("guest coordinates");return new Event(Kind.POINTER,0,(int)Math.round(x*(width-1)),(int)Math.round(y*(height-1)));}
        public int maximumEncodedBytes(){return 64;}
    }
    /** Share one limiter across the authoritative server, not one per incoming packet. */
    public static final class Limiter {
        private static final class Rate {int refs,count;long start;Rate(long now){start=now;}}
        private final Map<UUID,Rate> owners=new HashMap<>();
        synchronized void join(UUID owner,long now){Rate r=owners.get(owner);if(r==null){Iterator<Rate> it=owners.values().iterator();while(it.hasNext()){Rate old=it.next();if(old.refs==0&&now>=old.start&&now-old.start>=1000)it.remove();}if(owners.size()>=32)throw new IllegalStateException("input owner quota");r=new Rate(now);owners.put(owner,r);}if(now<r.start)throw new IllegalStateException("input clock");r.refs++;}
        synchronized void leave(UUID owner){Rate r=owners.get(owner);if(r!=null){if(r.refs<=0)throw new IllegalStateException("input accounting");r.refs--;}}
        synchronized void admit(UUID owner,long now){Rate r=owners.get(owner);if(r==null||now<r.start)throw new IllegalStateException("input clock");if(now-r.start>=1000){r.start=now;r.count=0;}if(r.count>=30)throw new IllegalStateException("input rate");r.count++;}
    }
    private final SessionV2 session;private final Limiter limiter;private final Deque<Event> queue=new ArrayDeque<>();private boolean focused,closed,releasePending;
    public GuestInputV2(SessionV2 session,Limiter limiter,long now){this.session=Objects.requireNonNull(session);this.limiter=Objects.requireNonNull(limiter);if(now<0||session.revoked())throw new IllegalStateException("closed session");limiter.join(session.identity.owner,now);try{session.onRevoke(this::close);}catch(RuntimeException e){limiter.leave(session.identity.owner);throw e;}}
    public synchronized void focus(Identity actor,long now){session.check(actor,session.id,session.generation,session.epoch,session.canvas,now);if(closed)throw new IllegalStateException("closed input");focused=true;}
    public synchronized void offer(Identity actor,UUID id,long generation,long epoch,String canvas,Event event,long now){session.check(actor,id,generation,epoch,canvas,now);if(!focused||closed||event==null||event.maximumEncodedBytes()>256)throw new SecurityException("input focus/bound");if(queue.size()>=32)throw new IllegalStateException("input queue");limiter.admit(session.identity.owner,now);queue.addLast(event);}
    public synchronized Event poll(){if(releasePending){releasePending=false;return new Event(Kind.RELEASE_ALL,0,0,0);}return queue.pollFirst();}
    public synchronized void loseFocus(){focused=false;queue.clear();releasePending=true;}
    @Override public synchronized void close(){if(closed)return;closed=true;loseFocus();limiter.leave(session.identity.owner);}
}
