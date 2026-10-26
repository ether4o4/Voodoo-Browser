package com.ether4o4.voodoo;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceEpochTest {
    @Test public void stoppedPermissionCannotStartNewSpeechRequest(){
        VoiceEpoch v=new VoiceEpoch();v.next();v.awaitPermission();v.next();
        assertFalse(v.consumePermission());v.awaitPermission();assertTrue(v.consumePermission());assertFalse(v.consumePermission());
    }
    @Test public void delayedUtteranceCannotFinishItsReplacement(){
        VoiceEpoch v=new VoiceEpoch();v.next();long old=v.current();v.next();long current=v.current();
        assertFalse(v.utterance("voodoo-"+old,current));assertFalse(v.utterance("voodoo-"+old,old));
        assertTrue(v.utterance("voodoo-"+current,current));
    }
}
