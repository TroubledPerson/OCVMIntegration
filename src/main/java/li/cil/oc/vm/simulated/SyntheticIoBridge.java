package li.cil.oc.vm.simulated;

import java.util.UUID;
import li.cil.oc.api.vm.v2.*;
import li.cil.oc.api.vm.v2.ProtocolV2.Identity;

/** API2 raw-pixel/input fixture bridge only. No GL, screen packet, image decoder or OS input. */
public final class SyntheticIoBridge implements AutoCloseable {
 private final QmpFixtureController controller;private final SessionV2 session;
 private final FrameAssemblerV2 frames;private final GuestInputV2 input;
 private boolean closed,released;
 public SyntheticIoBridge(QmpFixtureController controller,FrameAssemblerV2.Budget budget,GuestInputV2.Limiter limiter,long now){
  this.controller=controller;session=controller.fixtureSession();frames=new FrameAssemblerV2(session,budget,now);
  try{input=new GuestInputV2(session,limiter,now);}catch(RuntimeException failure){frames.close();throw failure;}
 }
 private void running(){if(closed||controller.state()!=QmpFixtureController.State.RUNNING)throw new IllegalStateException("synthetic display/input requires simulated running state");}
 public void begin(Identity actor,FrameAssemblerV2.Header header,long now){running();frames.begin(actor,header,now);}
 public boolean chunk(Identity actor,FrameAssemblerV2.Header header,int offset,byte[] bytes,long now){running();return frames.chunk(actor,header,offset,bytes,now);}
 public void copy(Identity actor,FrameAssemblerV2.Header header,byte[] destination,long now){running();frames.copyLatest(actor,header,destination,now);}
 public void focus(Identity actor,long now){running();input.focus(actor,now);released=false;}
 public void offer(Identity actor,UUID id,long generation,long epoch,String canvas,GuestInputV2.Event event,long now){running();input.offer(actor,id,generation,epoch,canvas,event,now);}
 public GuestInputV2.Event pollInput(){if(controller.state()!=QmpFixtureController.State.RUNNING&&!released){input.loseFocus();released=true;}return input.poll();}
 public void loseFocus(){controller.fixtureSession();input.loseFocus();released=true;}
 @Override public void close(){controller.fixtureSession();if(closed)return;closed=true;frames.close();input.close();released=true;}
 public boolean simulated(){return true;}
 public boolean mayExecuteGuest(){return false;}
}
