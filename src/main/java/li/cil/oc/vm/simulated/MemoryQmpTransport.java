package li.cil.oc.vm.simulated;

import java.util.*;

/** Memory-only transcript queues. No stream, endpoint, path, process or native adapter exists. */
public final class MemoryQmpTransport implements AutoCloseable {
 public static final int MAX_CHUNK=16384,MAX_QUEUE=32,MAX_LIFETIME_BYTES=262144;
 private final Deque<byte[]> incoming=new ArrayDeque<>(),outgoing=new ArrayDeque<>();
 private int bytes;private boolean closed,claimed;private Integer exit;
 synchronized void claim(){if(claimed||closed||exit!=null)throw new IllegalStateException("fixture transport already bound");claimed=true;}
 private void add(Deque<byte[]> queue,byte[] data){
  if(closed||exit!=null)throw new IllegalStateException("fixture transport unavailable");
  if(data==null||data.length==0||data.length>MAX_CHUNK||queue.size()>=MAX_QUEUE||data.length>MAX_LIFETIME_BYTES-bytes)throw new IllegalArgumentException("fixture byte/queue bound");
  queue.addLast(data.clone());bytes+=data.length;
 }
 public synchronized void feedFixture(byte[] data){add(incoming,data);}
 synchronized void send(byte[] data){add(outgoing,data);}
 synchronized byte[] receive(){return incoming.pollFirst();}
 public synchronized List<byte[]> takeRequests(){List<byte[]> result=new ArrayList<>(outgoing.size());while(!outgoing.isEmpty())result.add(outgoing.removeFirst());return result;}
 public synchronized void observeFixtureExit(int code){if(closed||exit!=null)throw new IllegalStateException("fixture exit already observed");exit=code;}
 synchronized Integer exitCode(){return exit;}
 public synchronized int retainedChunks(){return incoming.size()+outgoing.size();}
 public synchronized int lifetimeBytes(){return bytes;}
 @Override public synchronized void close(){if(closed)return;closed=true;incoming.clear();outgoing.clear();}
 public synchronized boolean closed(){return closed;}
 public boolean mayExecuteGuest(){return false;}
}
