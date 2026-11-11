package li.cil.oc.api.vm.v2;

import li.cil.oc.vm.json.BoundedJson;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Pure data contract for an optional future out-of-process backend. No executable discovery. */
public final class ProtocolV2 {
    public static final int VERSION=2, MAX_CONTROL_BYTES=16384;
    public enum Operation { STATUS, START, STOP, REVOKE }
    public enum Error { NONE, MISSING_ADDON, OFFLINE_PROVIDER, UNQUALIFIED_PACK, UNSUPPORTED_PROFILE,
        AUTHORIZATION_REQUIRED, RUNTIME_DISABLED, BAD_MESSAGE, STALE_SESSION, LIMIT, FAILED, REVOKED }
    public enum Device { HDD, CD, FLOPPY }
    private ProtocolV2(){}
    public static UUID uuid(String value){
        if(value==null||value.length()!=36)throw new IllegalArgumentException("invalid identifier");
        UUID id=UUID.fromString(value);if(!id.toString().equals(value))throw new IllegalArgumentException("noncanonical identifier");return id;
    }
    public static String hash(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("hash required");return value;}
    private static String id(String value){if(value==null||!value.matches("[A-Za-z0-9_.+:-]{1,80}"))throw new IllegalArgumentException("profile identifier required");return value;}
    public static final class Identity {
        public final UUID world, machine, owner, pack;
        public Identity(UUID world,UUID machine,UUID owner,UUID pack){this.world=Objects.requireNonNull(world);this.machine=Objects.requireNonNull(machine);this.owner=Objects.requireNonNull(owner);this.pack=Objects.requireNonNull(pack);}
        @Override public boolean equals(Object o){if(!(o instanceof Identity))return false;Identity i=(Identity)o;return world.equals(i.world)&&machine.equals(i.machine)&&owner.equals(i.owner)&&pack.equals(i.pack);}
        @Override public int hashCode(){return Objects.hash(world,machine,owner,pack);}
        public String caseKey(){return world+"/"+machine;}
    }
    public static final class Host {
        public final String os,architecture;
        private Host(String os,String architecture){this.os=os;this.architecture=architecture;}
        /** Inject properties from the authoritative host; this does not probe binaries or certify support. */
        public static Host declared(String os,String arch){
            if(os==null||arch==null||os.length()>64||arch.length()>64)throw new IllegalArgumentException("unknown platform");
            os=os.trim().toLowerCase(Locale.ROOT);arch=arch.trim().toLowerCase(Locale.ROOT);
            String platform=os.startsWith("windows")?"windows":os.equals("linux")?"linux":os.equals("mac os x")||os.equals("macos")||os.equals("darwin")?"macos":null;
            String cpu=arch.equals("amd64")||arch.equals("x86_64")?"x86_64":arch.equals("aarch64")||arch.equals("arm64")?"arm64":null;
            if(platform==null||cpu==null)throw new IllegalArgumentException("unsupported declared platform");return new Host(platform,cpu);
        }
        public boolean runtimeQualified(){return false;}
    }
    public static final class BackendProfile {
        public final Host host;public final UUID pack;public final String packHash,guest,machine,machineVersion,cpu,firmware,firmwareHash,firmwareLicense;
        public BackendProfile(Host host,UUID pack,String packHash,String guest,String machine,String version,String cpu,String firmware,String firmwareHash,String license){
            this.host=Objects.requireNonNull(host);this.pack=Objects.requireNonNull(pack);this.packHash=hash(packHash);
            if(!Arrays.asList("x86_64","aarch64","riscv64").contains(guest))throw new IllegalArgumentException("unknown guest ISA");
            this.guest=guest;this.machine=id(machine);machineVersion=id(version);this.cpu=id(cpu);this.firmware=id(firmware);this.firmwareHash=hash(firmwareHash);firmwareLicense=id(license);
        }
        public Error support(){return Error.UNQUALIFIED_PACK;}
    }
    public static final class Capabilities {
        public final int version=VERSION;public final boolean syntheticFrames=true,syntheticInput=true;
        public boolean mayExecuteGuest(){return false;}
    }
    public interface Transport { Reply request(Identity authoritativeActor,byte[] control,long now); }
    public static final class Reply {
        public final int version=VERSION;public final UUID session,correlation;public final Error error;
        public final LifecycleV2.State state;
        public final boolean simulated=false;
        Reply(Message request,Error error){session=request.session;correlation=request.correlation;this.error=error;state=error==Error.REVOKED?LifecycleV2.State.REVOKED:LifecycleV2.State.OFFLINE_ONLY;}
        public boolean mayExecuteGuest(){return false;}
    }
    public interface Provider {
        int apiVersion(); String backend(); Capabilities capabilities();
        default Transport openOffline(SessionV2 session){return new OfflineAdapterV2(session);}
    }
    private static Provider provider;
    public static synchronized void register(Provider candidate){
        if(candidate==null||candidate.apiVersion()!=VERSION||!"qemu-tcg".equals(candidate.backend())||candidate.capabilities()==null||candidate.capabilities().mayExecuteGuest())throw new IllegalArgumentException("incompatible API2 provider");
        if(provider!=null&&provider!=candidate)throw new IllegalStateException("API2 provider already registered");provider=candidate;
    }
    public static synchronized void unregister(Provider candidate){if(provider==candidate)provider=null;}
    public static synchronized Error status(){return provider==null?Error.MISSING_ADDON:Error.OFFLINE_PROVIDER;}
    public static boolean mayExecuteGuest(){return false;}

    public static final class Message {
        public final UUID session,correlation;public final Identity identity;public final long generation,sequence,epoch;public final String canvas;public final Operation operation;
        public Message(UUID session,UUID correlation,Identity identity,long generation,long sequence,long epoch,String canvas,Operation operation){
            this.session=Objects.requireNonNull(session);this.correlation=Objects.requireNonNull(correlation);this.identity=Objects.requireNonNull(identity);
            if(generation<1||sequence<1||epoch<0)throw new IllegalArgumentException("invalid generation/sequence");
            this.generation=generation;this.sequence=sequence;this.epoch=epoch;this.canvas=hash(canvas);this.operation=Objects.requireNonNull(operation);
        }
    }
    /** UTF-8 size and malformed-byte gate precede decoding. Exact flat schema, no payload/QMP passthrough. */
    public static Message decode(byte[] encoded){
        try{
            Map<String,Object> root=BoundedJson.object(encoded,BoundedJson.CONTROL);
            BoundedJson.keys(root,"version","session","correlation","world","case","owner","package","generation","sequence","epoch","canvas","operation");
            if(BoundedJson.integer(root.get("version"))!=VERSION)throw new IllegalArgumentException("incompatible control schema");
            return new Message(uuid(BoundedJson.string(root.get("session"))),uuid(BoundedJson.string(root.get("correlation"))),
                new Identity(uuid(BoundedJson.string(root.get("world"))),uuid(BoundedJson.string(root.get("case"))),
                uuid(BoundedJson.string(root.get("owner"))),uuid(BoundedJson.string(root.get("package")))),
                BoundedJson.integer(root.get("generation")),BoundedJson.integer(root.get("sequence")),BoundedJson.integer(root.get("epoch")),
                BoundedJson.string(root.get("canvas")),Operation.valueOf(BoundedJson.string(root.get("operation"))));
        }catch(RuntimeException invalid){throw new IllegalArgumentException("invalid API2 control message",invalid);}
    }

}
