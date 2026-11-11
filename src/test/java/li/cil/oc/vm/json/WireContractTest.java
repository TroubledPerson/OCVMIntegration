package li.cil.oc.vm.json;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import li.cil.oc.api.vm.v2.ProtocolV2;
import li.cil.oc.api.vm.VmManifest;

public class WireContractTest {
    static String uuid(){return UUID.randomUUID().toString();}
    static String control(){return "{\"version\":2,\"session\":\""+uuid()+"\",\"correlation\":\""+uuid()+"\",\"world\":\""+uuid()+"\",\"case\":\""+uuid()+"\",\"owner\":\""+uuid()+"\",\"package\":\""+uuid()+"\",\"generation\":1,\"sequence\":1,\"epoch\":0,\"canvas\":\""+BoundedJsonTest.repeat("a",64)+"\",\"operation\":\"STATUS\"}";}
    static void badControl(String s){BoundedJsonTest.rejects(()->ProtocolV2.decode(BoundedJsonTest.bytes(s)));}
    @Test public void validControlAndExactTypedNumbers(){
        String wire=control();assertEquals(ProtocolV2.Operation.STATUS,ProtocolV2.decode(BoundedJsonTest.bytes(wire)).operation);
        for(String n:new String[]{"\"2\"","2.0","2e0","02","-0","9223372036854775808"})
            badControl(wire.replace("\"version\":2","\"version\":"+n));
    }
    @Test public void controlDuplicatesEscapesUnknownNestedAndTrailingFail(){
        String wire=control();
        badControl(wire.replace("\"version\":2","\"version\":2,\"\\u0076ersion\":2"));
        badControl(wire.replace("\"version\":2","\"version\":2,\"extra\":0"));
        badControl(wire.replace("\"version\":2","\"version\":{}"));
        badControl(wire+"{}");
    }
    static String manifest(){
        String[] ids={uuid(),uuid(),uuid(),uuid(),uuid(),uuid(),uuid(),uuid(),uuid(),uuid()};
        return "{\"schema\":1,\"backend\":\"qemu-tcg\",\"packageId\":\""+ids[0]+"\",\"worldId\":\""+ids[1]+"\",\"caseId\":\""+ids[2]+"\",\"raidId\":\""+ids[3]+"\",\"volumeId\":\""+ids[4]+"\",\"architecture\":\"x86_64\",\"machine\":\"pc\",\"firmware\":\"bios\",\"firmwareSha256\":\""+BoundedJsonTest.repeat("a",64)+"\",\"diskPath\":\"library/disk.raw\",\"capacityPolicy\":\"literal-bytes\",\"capacityBytes\":3072,\"memberIds\":[\""+ids[5]+"\",\""+ids[6]+"\",\""+ids[7]+"\"],\"memberBytes\":[1024,1024,1024],\"hostOS\":\"windows\",\"hostArchitecture\":\"x86_64\",\"machineVersion\":\"1\",\"cpuModel\":\"qemu64\",\"firmwareLicense\":\"test\",\"diskFormat\":\"raw\",\"nvramId\":\""+ids[8]+"\",\"ownerId\":\""+ids[9]+"\",\"vcpus\":1,\"ramMiB\":16,\"externalDependencies\":[]}";
    }
    @Test public void aggregateManifestKeepsShapeAndNoExecution(){
        VmManifest.Plan p=VmManifest.validate(manifest());assertEquals(3072,p.capacityBytes);assertEquals(3,p.members.size());assertFalse(p.executable());
    }
    @Test public void manifestTypedNumbersDuplicateAndTrailingRemainRejected(){
        String wire=manifest();
        for(String n:new String[]{"\"1\"","1.0","1e0","01","9223372036854775808"})
            BoundedJsonTest.rejects(()->VmManifest.validate(wire.replace("\"schema\":1","\"schema\":"+n)));
        BoundedJsonTest.rejects(()->VmManifest.validate(wire.replace("\"schema\":1","\"schema\":1,\"\\u0073chema\":1")));
        BoundedJsonTest.rejects(()->VmManifest.validate(wire+" {}"));
    }
}
