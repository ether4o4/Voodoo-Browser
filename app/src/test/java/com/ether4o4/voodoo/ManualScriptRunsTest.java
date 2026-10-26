package com.ether4o4.voodoo;
import org.junit.Test;
import static org.junit.Assert.*;
public class ManualScriptRunsTest {
    @Test public void repeatedRunsSurviveTheirOwnReloadButNotTheNextNavigation(){
        ManualScriptRuns<String,String> runs=new ManualScriptRuns<>();
        runs.schedule("tab","first");assertFalse(runs.navigating("tab"));assertTrue(runs.finished("tab"));
        runs.schedule("tab","second");assertFalse(runs.navigating("tab"));assertEquals("second",runs.get("tab"));
        assertTrue(runs.finished("tab"));assertTrue(runs.navigating("tab"));assertNull(runs.get("tab"));
    }
    @Test public void closingOneTabDoesNotRemoveAnotherDraft(){
        ManualScriptRuns<String,String> runs=new ManualScriptRuns<>();runs.schedule("a","A");runs.schedule("b","B");runs.remove("a");assertNull(runs.get("a"));assertEquals("B",runs.get("b"));assertFalse(runs.finished("a"));
    }
}
