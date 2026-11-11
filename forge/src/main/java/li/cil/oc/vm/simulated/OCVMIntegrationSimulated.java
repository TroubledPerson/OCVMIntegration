package li.cil.oc.vm.simulated;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** Optional source-only addon. Registration stays OFFLINE; fixtures are explicit. */
@Mod(modid="ocpp_vmintegration",name="OC++ VM Integration (Simulated)",version="0.3.0-dev.3",acceptedMinecraftVersions="[1.12.2]",dependencies="required-after:opencomputers@[1.8.10-ocpp-dev.20,)",acceptableRemoteVersions="*")
public final class OCVMIntegrationSimulated {
    @Mod.EventHandler public void preInit(FMLPreInitializationEvent event) {
        OfflineProviders.register();
    }
}
