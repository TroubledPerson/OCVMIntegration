package li.cil.oc.vm.simulated;

import li.cil.oc.api.vm.SuperBios;
import li.cil.oc.api.vm.v2.ProtocolV2;

/** Pure registration boundary shared by the Forge entrypoint and offline tests. */
public final class OfflineProviders {
    public static void register() {
        SuperBios.register(new Provider1());
        ProtocolV2.register(new Provider2());
    }
    public static final class Provider1 implements SuperBios.Provider {
        public int apiVersion() { return 1; }
        public String backend() { return "qemu-tcg"; }
    }
    public static final class Provider2 implements ProtocolV2.Provider {
        public int apiVersion() { return 2; }
        public String backend() { return "qemu-tcg"; }
        public ProtocolV2.Capabilities capabilities() { return new ProtocolV2.Capabilities(); }
    }
    private OfflineProviders() {}
}
