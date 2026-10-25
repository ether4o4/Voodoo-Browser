package com.ether4o4.voodoo;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;
public class BridgePolicyTest {
    interface Checked { void run()throws Exception; }
    static void rejected(Checked f){try{f.run();fail("Expected rejection");}catch(Exception expected){}}
    @Test public void sitesAreExactHttpsOrigins()throws Exception{
        assertEquals("https://example.com",BridgePolicy.origin("https://EXAMPLE.com:443/path"));
        assertEquals(2,BridgePolicy.sites("example.com\nhttps://other.example/").size());
        rejected(()->BridgePolicy.origin("https://user:secret@example.com"));
        rejected(()->BridgePolicy.origin("http://example.com"));
        rejected(()->BridgePolicy.origin("https://appassets.androidplatform.net"));
        rejected(()->BridgePolicy.origin("https://appassets.androidplatform.net."));
        rejected(()->BridgePolicy.sites("https://example.com/path"));
    }
    JSONObject command(String op)throws Exception{return new JSONObject().put("id","command-123").put("op",op).put("deadline",11000);}
    @Test public void deadlinesAndLimitsFailClosed()throws Exception{
        BridgePolicy.validate(command("scroll"),1000);
        rejected(()->BridgePolicy.validate(command("scroll"),11000));
        rejected(()->BridgePolicy.validate(command("scroll").put("deadline",100000),1000));
        rejected(()->BridgePolicy.validate(command("scroll").put("pixels",Integer.MIN_VALUE),1000));
        rejected(()->BridgePolicy.validate(command("evaluate_js"),1000));
        rejected(()->BridgePolicy.validate(command("navigate").put("url","javascript:alert(1)"),1000));
    }
    @Test public void mutationsRequireSnapshotAndDocumentVersion()throws Exception{
        JSONObject c=command("type").put("ref","e0").put("documentVersion",1).put("snapshotId","snapshot").put("text","hello");BridgePolicy.validate(c,1000);
        c.remove("snapshotId");rejected(()->BridgePolicy.validate(c,1000));
        rejected(()->BridgePolicy.validate(command("wait_for").put("text","ok").put("timeoutMs",60000),1000));
    }
}
