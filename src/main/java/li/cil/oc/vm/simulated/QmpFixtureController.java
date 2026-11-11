package li.cil.oc.vm.simulated;

import java.util.*;
import li.cil.oc.api.vm.v2.*;
import li.cil.oc.api.vm.v2.ProtocolV2.*;

/** Explicitly simulated controller on an API2 authoritative dispatcher. No native runner/IPC. */
public final class QmpFixtureController implements AutoCloseable {
 public static final int MAX_PENDING=16,MAX_REQUESTS=128,MAX_RESULTS=16;
 public static final long TIMEOUT=2000,IDLE=30000;
 public enum State { GREETING,NEGOTIATING,STOPPED,STARTING,RUNNING,PAUSED,STOPPING,FAILED,REVOKED }
 public enum ResultCode { ACCEPTED,COMPLETE,NOT_READY,LIMIT,ERROR,TIMEOUT,REVOKED }
 public static final class Result {
  public final UUID correlation;public final ResultCode code;public final boolean simulated=true;
  Result(UUID correlation,ResultCode code){this.correlation=correlation;this.code=code;}
  public boolean mayExecuteGuest(){return false;}
 }
 private static final class Pending {
  final QmpCodec.Command command;final UUID correlation;final long deadline;boolean acknowledged;
  Pending(QmpCodec.Command command,UUID correlation,long now){this.command=command;this.correlation=correlation;deadline=now>Long.MAX_VALUE-TIMEOUT?Long.MAX_VALUE:now+TIMEOUT;}
 }
 private final SessionV2 session;private final MemoryQmpTransport io;private final QmpCodec codec=new QmpCodec();
 private final Map<UUID,Pending> pending=new LinkedHashMap<>();private final Set<UUID> seen=new HashSet<>();private final Deque<Result> results=new ArrayDeque<>();
 private final Thread dispatcher=Thread.currentThread();
 private State state=State.GREETING;private long now,lastRequest,startUntil,stopUntil;private final long greetingUntil;private boolean closed,quarantined;private int ignored,dropped;
 public QmpFixtureController(SessionV2 session,MemoryQmpTransport io,long now){
  this.session=Objects.requireNonNull(session);this.io=Objects.requireNonNull(io);if(now<0)throw new IllegalArgumentException("fixture clock");this.now=lastRequest=now;greetingUntil=now>Long.MAX_VALUE-TIMEOUT?Long.MAX_VALUE:now+TIMEOUT;
  session.check(session.identity,session.id,session.generation,session.epoch,session.canvas,now);io.claim();try{session.onRevoke(this::revoked);}catch(RuntimeException denied){io.close();throw denied;}
 }
 private void check(Identity actor,long t){
  session.check(actor,session.id,session.generation,session.epoch,session.canvas,t);
  if(closed)throw new IllegalStateException("fixture closed");
  if(t<now){session.revoke();throw new IllegalStateException("fixture clock rollback");}now=t;
 }
 public Result submit(Identity authoritativeActor,byte[] encoded,long t){
  Message request=ProtocolV2.decode(encoded);check(authoritativeActor,t);
  if(seen.contains(request.correlation))throw new SecurityException("fixture correlation replay");
  if(seen.size()>=MAX_REQUESTS)throw new IllegalStateException("fixture lifetime request bound");
  session.accept(authoritativeActor,request,t);session.complete(request.correlation); // Existing API2 contract completes synchronously; async results are fixture-owned.
  seen.add(request.correlation);lastRequest=t;
  if(request.operation==Operation.REVOKE){session.revoke();return new Result(request.correlation,ResultCode.REVOKED);}
  if(state==State.GREETING||state==State.NEGOTIATING)return new Result(request.correlation,ResultCode.NOT_READY);
  if(pending.size()>=MAX_PENDING)return new Result(request.correlation,ResultCode.LIMIT);
  try{switch(request.operation){
   case STATUS:send(QmpCodec.Command.STATUS,request.correlation);break;
   case START:
    if(state!=State.STOPPED&&state!=State.PAUSED)return new Result(request.correlation,ResultCode.NOT_READY);
    send(QmpCodec.Command.CONTINUE,request.correlation);state=State.STARTING;startUntil=now>Long.MAX_VALUE-TIMEOUT?Long.MAX_VALUE:now+TIMEOUT;break;
   case STOP:
    if(state==State.STOPPED)return new Result(request.correlation,ResultCode.COMPLETE);
    if(state==State.STOPPING)return new Result(request.correlation,ResultCode.ACCEPTED);
    if(state!=State.RUNNING&&state!=State.PAUSED&&state!=State.STARTING)return new Result(request.correlation,ResultCode.NOT_READY);
    send(QmpCodec.Command.POWER_DOWN,request.correlation);state=State.STOPPING;stopUntil=now>Long.MAX_VALUE-2*TIMEOUT?Long.MAX_VALUE:now+2*TIMEOUT;break;
   default:throw new IllegalArgumentException("fixture operation");
  }}catch(RuntimeException transportFailure){fail();return new Result(request.correlation,ResultCode.ERROR);}
  return new Result(request.correlation,ResultCode.ACCEPTED);
 }
 private void send(QmpCodec.Command command,UUID correlation){
  if(pending.size()>=MAX_PENDING)throw new IllegalStateException("fixture pending bound");UUID id=UUID.randomUUID();io.send(QmpCodec.command(command,id));pending.put(id,new Pending(command,correlation,now));
 }
 public void pump(long t){
  dispatcher();if(closed)return;
  if(t>=now&&t-lastRequest>=IDLE){session.revoke();return;}
  check(session.identity,t);
  if(state==State.GREETING&&t>=greetingUntil){fail();return;}
  if(state==State.STARTING&&t>=startUntil){fail();return;}
  if(state==State.STOPPING&&t>=stopUntil){fail();return;}
  if(io.exitCode()!=null){
   for(Pending p:pending.values())if(p.command==QmpCodec.Command.QUIT&&t>=p.deadline){fail();return;}
   if(state==State.STOPPING&&io.exitCode()==0){for(Pending p:pending.values())result(p.correlation,ResultCode.COMPLETE);pending.clear();state=State.STOPPED;io.close();codec.close();closed=true;}
   else fail();return;
  }
  List<Pending> expired=new ArrayList<>();Iterator<Map.Entry<UUID,Pending>> it=pending.entrySet().iterator();while(it.hasNext()){Pending p=it.next().getValue();if(t>=p.deadline){it.remove();expired.add(p);}}
  for(Pending p:expired){
    if(p.command==QmpCodec.Command.POWER_DOWN){try{send(QmpCodec.Command.QUIT,p.correlation);}catch(RuntimeException failure){fail();return;}}
   else if(p.command==QmpCodec.Command.STATUS)result(p.correlation,ResultCode.TIMEOUT);
   else {fail();return;}
  }
  try{byte[] bytes;while(!closed&&(bytes=io.receive())!=null)codec.feed(bytes,this::message);}catch(RuntimeException invalid){fail();}
 }
 private void message(QmpCodec.Message m){
  if(m.kind==QmpCodec.Kind.GREETING){if(state!=State.GREETING)throw new IllegalStateException("duplicate greeting");state=State.NEGOTIATING;send(QmpCodec.Command.CAPABILITIES,null);return;}
  if(m.kind==QmpCodec.Kind.EVENT){
   if(state==State.GREETING||state==State.NEGOTIATING)throw new IllegalStateException("event before negotiation");
   if(m.event.equals("RESUME")){if(state==State.STARTING||state==State.RUNNING)state=State.RUNNING;else throw new IllegalStateException("unexpected resume");}
   else if(m.event.equals("STOP")){if(state==State.RUNNING||state==State.STARTING)state=State.PAUSED;}
   // SHUTDOWN is evidence only. A clean stop still requires explicit fixture exit(0).
   return;
  }
  Pending p=pending.remove(m.id);if(p==null){if(ignored<Integer.MAX_VALUE)ignored++;return;}
  if(p.acknowledged){pending.put(m.id,p);if(ignored<Integer.MAX_VALUE)ignored++;return;}
  if(m.kind==QmpCodec.Kind.ERROR){result(p.correlation,ResultCode.ERROR);if(p.command!=QmpCodec.Command.STATUS)fail();return;}
  if(p.command==QmpCodec.Command.CAPABILITIES){if(state!=State.NEGOTIATING||!m.body.isEmpty())throw new IllegalStateException("capabilities acknowledgement");state=State.STOPPED;}
  else if(p.command==QmpCodec.Command.STATUS){Object status=m.body.get("status");if(!(status instanceof String)||!Arrays.asList("running","paused","shutdown","prelaunch").contains(status))throw new IllegalArgumentException("fixture status subset");result(p.correlation,ResultCode.COMPLETE);}
  else if(p.command==QmpCodec.Command.CONTINUE){if(!m.body.isEmpty())throw new IllegalArgumentException("continue acknowledgement");result(p.correlation,ResultCode.COMPLETE);}
  else {if(!m.body.isEmpty())throw new IllegalArgumentException("stop acknowledgement");p.acknowledged=true;pending.put(m.id,p);} // Ack alone is not exit; retain original bounded deadline.
 }
 private void result(UUID correlation,ResultCode code){if(correlation==null)return;if(results.size()==MAX_RESULTS){results.removeFirst();if(dropped<Integer.MAX_VALUE)dropped++;}results.addLast(new Result(correlation,code));}
 private void fail(){quarantined=true;state=State.FAILED;for(Pending p:pending.values())result(p.correlation,ResultCode.ERROR);pending.clear();closed=true;io.close();codec.close();}
 private void revoked(){if(state!=State.FAILED)state=State.REVOKED;closed=true;pending.clear();results.clear();codec.close();io.close();}
 @Override public void close(){session.revoke();}
 private void dispatcher(){if(Thread.currentThread()!=dispatcher)throw new IllegalStateException("fixture dispatcher required");}
 public Result pollResult(){dispatcher();return results.pollFirst();}
 public State state(){dispatcher();return state;}
 public int pending(){dispatcher();return pending.size();}
 public int ignoredReplies(){dispatcher();return ignored;}
 public int droppedResults(){dispatcher();return dropped;}
 public boolean requiresReconciliation(){dispatcher();return quarantined;}
 public boolean simulated(){return true;}
 public boolean mayExecuteGuest(){return false;}
 public boolean deletesData(){return false;}
 SessionV2 fixtureSession(){dispatcher();return session;}
}
