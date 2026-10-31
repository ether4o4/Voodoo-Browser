package com.ether4o4.voodoo;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ScriptChangesTest {
    private JSONObject script(String id,String code,String mode)throws Exception{
        return ScriptChanges.mode(new JSONObject().put("id",id).put("code",code).put("name",id),mode);
    }
    @Test public void manualAndSavedModesNeverEnableAutomaticExecution()throws Exception{
        JSONObject value=script("s","source","automatic");assertTrue(value.getBoolean("enabled"));
        ScriptChanges.mode(value,"manual");assertFalse(value.getBoolean("enabled"));assertEquals("manual",ScriptChanges.mode(value));
        ScriptChanges.mode(value,"saved");assertFalse(value.getBoolean("enabled"));assertEquals("saved",ScriptChanges.mode(value));
    }
    @Test public void existingScriptsKeepTheirOldAutomaticSetting()throws Exception{
        assertEquals("automatic",ScriptChanges.mode(new JSONObject().put("enabled",true)));
        assertEquals("saved",ScriptChanges.mode(new JSONObject().put("enabled",false)));
    }
    @Test public void inactiveEditsDoNotRestartUnrelatedAutomaticScripts()throws Exception{
        JSONArray before=new JSONArray().put(script("auto","A","automatic")).put(script("manual","B","manual"));
        JSONArray after=new JSONArray().put(script("auto","A","automatic")).put(script("manual","changed","manual")).put(script("saved","C","saved"));
        assertFalse(ScriptChanges.automaticChanged(before,after));
    }
    @Test public void automaticSourceChangesAndDisablingRequireNewRegistrations()throws Exception{
        JSONArray before=new JSONArray().put(script("a","A","automatic"));
        assertTrue(ScriptChanges.automaticChanged(before,new JSONArray().put(script("a","B","automatic"))));
        assertTrue(ScriptChanges.automaticChanged(before,new JSONArray().put(script("a","A","manual"))));
        assertTrue(ScriptChanges.automaticChanged(before,new JSONArray()));
    }
    @Test public void renamingAndReorderingDoNotInterruptExecution()throws Exception{
        JSONArray before=new JSONArray().put(script("a","A","automatic")).put(script("b","B","automatic"));
        JSONArray after=new JSONArray().put(script("b","B","automatic").put("name","renamed")).put(script("a","A","automatic"));
        assertFalse(ScriptChanges.automaticChanged(before,after));
    }
    @Test public void changingOrRemovingSavedManualSourceRevokesItsCurrentRun()throws Exception{
        JSONObject running=script("m","A","manual");JSONArray before=new JSONArray().put(running);
        assertFalse(ScriptChanges.manualChanged(running,before,new JSONArray().put(new JSONObject(running.toString()).put("name","rename"))));
        assertTrue(ScriptChanges.manualChanged(running,before,new JSONArray().put(script("m","B","manual"))));
        assertTrue(ScriptChanges.manualChanged(running,before,new JSONArray().put(script("m","A","saved"))));
        assertTrue(ScriptChanges.manualChanged(running,before,new JSONArray()));
        assertFalse(ScriptChanges.manualChanged(running,new JSONArray(),new JSONArray()));
    }
}
