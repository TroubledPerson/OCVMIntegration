package li.cil.oc.api.vm;

/** Optional provider contract. Version1 deliberately has no guest execution API. */
public final class SuperBios {
    public static final int API_VERSION=1, MISSING=0, OFFLINE=1;
    public interface Provider { int apiVersion(); String backend(); }
    private static Provider provider;
    private SuperBios(){}
    public static synchronized void register(Provider candidate){
        if(candidate==null||candidate.apiVersion()!=API_VERSION||!"qemu-tcg".equals(candidate.backend()))throw new IllegalArgumentException("unsupported VM provider");
        if(provider!=null&&provider!=candidate)throw new IllegalStateException("VM provider already registered");
        provider=candidate;
    }
    public static synchronized int statusCode(){return provider==null?MISSING:OFFLINE;}
    public static synchronized void unregister(Provider candidate){if(provider==candidate)provider=null;}
    public static boolean mayExecuteGuest(){return false;}
    /** Status APIs contain no host paths, binary probes, native loads or listeners. */
    public static String statusKey(int code){return code==OFFLINE?"ocpp.superbios.offline":"ocpp.superbios.missing";}
}
