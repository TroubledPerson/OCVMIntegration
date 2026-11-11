package li.cil.oc.api.vm.v2;

import java.util.*;
import li.cil.oc.api.vm.v2.ProtocolV2.Identity;

/** Bounded raw BGRA fixture transport. No decoder, GL texture or Minecraft packet is created. */
public final class FrameAssemblerV2 implements AutoCloseable {
    public static final int CHUNK=32768,MAX_ENCODED=262144,MAX_RAW=640*480*4;
    public static final class Header {
        public final UUID session;public final long generation,epoch,frame;public final String canvas;public final int width,height,stride,bytes;
        public Header(UUID session,long generation,long epoch,String canvas,long frame,int width,int height,int stride,int bytes){
            this.session=Objects.requireNonNull(session);this.canvas=ProtocolV2.hash(canvas);
            if(generation<1||epoch<0||frame<1||width<1||width>640||height<1||height>480||stride!=(long)width*4||bytes!=(long)stride*height||bytes>MAX_RAW||bytes>MAX_ENCODED)throw new IllegalArgumentException("raw frame bound/format");
            this.generation=generation;this.epoch=epoch;this.frame=frame;this.width=width;this.height=height;this.stride=stride;this.bytes=bytes;
        }
        private boolean same(Header h){return h!=null&&session.equals(h.session)&&generation==h.generation&&epoch==h.epoch&&canvas.equals(h.canvas)&&frame==h.frame&&width==h.width&&height==h.height&&stride==h.stride&&bytes==h.bytes;}
    }
    public static final class Budget {
        private final long maximum;private long used;private final Map<String,Rate> cases=new HashMap<>();
        private static final class Rate {int users,frames,bytes;long start;Rate(long now){start=now;}}
        public Budget(long maximum){if(maximum<1||maximum>16*1024*1024)throw new IllegalArgumentException("aggregate frame budget");this.maximum=maximum;}
        synchronized void join(String key,long now){Rate r=cases.get(key);if(r==null){Iterator<Rate> it=cases.values().iterator();while(it.hasNext()){Rate old=it.next();if(old.users==0&&now>=old.start&&now-old.start>=1000)it.remove();}if(cases.size()>=32)throw new IllegalStateException("case quota");r=new Rate(now);cases.put(key,r);}if(r.users>=2||now<r.start)throw new IllegalStateException("case session/clock quota");r.users++;}
        synchronized void leave(String key){Rate r=cases.get(key);if(r!=null){if(r.users<=0)throw new IllegalStateException("case accounting");r.users--;}}
        synchronized void reserve(int bytes){if(bytes>maximum-used)throw new IllegalStateException("aggregate frame memory");used+=bytes;}
        synchronized void release(int bytes){used-=bytes;if(used<0)throw new IllegalStateException("frame accounting");}
        synchronized void rate(String key,long now,int bytes,boolean frame){Rate r=cases.get(key);if(r==null||now<r.start)throw new IllegalStateException("frame clock/session");if(now-r.start>=1000){r.start=now;r.frames=0;r.bytes=0;}if((frame&&r.frames>=4)||bytes>1048576-r.bytes)throw new IllegalStateException("frame rate");if(frame)r.frames++;r.bytes+=bytes;}
        public synchronized long used(){return used;}
    }
    private static final class Pending {final Header h;final byte[] pixels;final BitSet chunks=new BitSet();final long expires;int received;Pending(Header h,long now){this.h=h;pixels=new byte[h.bytes];expires=SessionV2.deadline(now,2000);}}
    private final SessionV2 session;private final Budget budget;private final String key;private final Map<Long,Pending> pending=new LinkedHashMap<>();
    private Pending displayed;private long latest;private boolean closed;
    public FrameAssemblerV2(SessionV2 session,Budget budget,long now){this.session=Objects.requireNonNull(session);this.budget=Objects.requireNonNull(budget);if(now<0||session.revoked())throw new IllegalStateException("closed session");key=session.identity.caseKey();budget.join(key,now);try{session.onRevoke(this::close);}catch(RuntimeException e){budget.leave(key);throw e;}}
    private void check(Identity actor,Header h,long now){session.check(actor,h.session,h.generation,h.epoch,h.canvas,now);if(closed)throw new IllegalStateException("frame closed");expire(now);}
    public synchronized void begin(Identity actor,Header h,long now){
        check(actor,h,now);if(h.frame<=latest||pending.containsKey(h.frame)||pending.size()+(displayed==null?0:1)>=2)throw new IllegalArgumentException("frame order/queue");
        budget.rate(key,now,0,true);budget.reserve(h.bytes+256);try{pending.put(h.frame,new Pending(h,now));}catch(RuntimeException|OutOfMemoryError e){budget.release(h.bytes+256);throw e;}
    }
    public synchronized boolean chunk(Identity actor,Header h,int offset,byte[] bytes,long now){
        check(actor,h,now);Pending p=pending.get(h.frame);
        if(p==null||!p.h.same(h)||bytes==null||offset<0||offset>=h.bytes||offset%CHUNK!=0||bytes.length!=Math.min(CHUNK,h.bytes-offset)||p.chunks.get(offset/CHUNK))throw new IllegalArgumentException("chunk geometry/replay");
        budget.rate(key,now,bytes.length,false);System.arraycopy(bytes,0,p.pixels,offset,bytes.length);p.chunks.set(offset/CHUNK);p.received+=bytes.length;
        if(p.received!=h.bytes)return false;
        if(displayed!=null)budget.release(displayed.h.bytes+256);displayed=p;latest=h.frame;pending.remove(h.frame);
        Iterator<Pending> it=pending.values().iterator();while(it.hasNext()){Pending old=it.next();if(old.h.frame<=latest){budget.release(old.h.bytes+256);it.remove();}}
        return true;
    }
    public synchronized long latestFrame(){return displayed==null?0:latest;}
    /** Copies into a caller-owned buffer; never returns or allocates an unbounded pixel copy. */
    public synchronized void copyLatest(Identity actor,Header expected,byte[] destination,long now){check(actor,expected,now);if(displayed==null||!displayed.h.same(expected)||destination==null||destination.length!=expected.bytes)throw new IllegalArgumentException("stale frame/copy bound");System.arraycopy(displayed.pixels,0,destination,0,destination.length);}
    public synchronized void expire(long now){Iterator<Pending> it=pending.values().iterator();while(it.hasNext()){Pending p=it.next();if(now<0||p.expires<=now){budget.release(p.h.bytes+256);it.remove();}}}
    @Override public synchronized void close(){if(closed)return;closed=true;for(Pending p:pending.values())budget.release(p.h.bytes+256);pending.clear();if(displayed!=null){budget.release(displayed.h.bytes+256);displayed=null;}budget.leave(key);}
}
