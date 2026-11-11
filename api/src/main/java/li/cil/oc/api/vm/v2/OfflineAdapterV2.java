package li.cil.oc.api.vm.v2;

import java.util.Objects;
import li.cil.oc.api.vm.v2.ProtocolV2.*;
import li.cil.oc.api.vm.v2.ProtocolV2.Error;

/** Typed in-memory adapter seam. No external runner/IPC or successful guest state exists. */
public final class OfflineAdapterV2 implements Transport {
    private final SessionV2 session;
    public OfflineAdapterV2(SessionV2 session){this.session=Objects.requireNonNull(session);}
    @Override public Reply request(Identity actor,byte[] encoded,long now){
        Message message=ProtocolV2.decode(encoded);session.accept(actor,message,now);
        Error error;
        switch(message.operation){
            case STATUS: error=Error.OFFLINE_PROVIDER;break;
            case START: case STOP: error=Error.RUNTIME_DISABLED;break;
            case REVOKE: error=Error.REVOKED;break;
            default: throw new IllegalArgumentException("operation unavailable");
        }
        session.complete(message.correlation);
        if(message.operation==Operation.REVOKE)session.revoke();
        return new Reply(message,error);
    }
}
