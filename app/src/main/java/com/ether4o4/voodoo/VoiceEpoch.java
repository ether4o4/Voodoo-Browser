package com.ether4o4.voodoo;

/** Tracks asynchronous microphone permission and speech callbacks across stop/restart. */
final class VoiceEpoch {
    private long epoch, permission=-1;
    long current(){return epoch;}
    void next(){epoch++;permission=-1;}
    void awaitPermission(){permission=epoch;}
    boolean consumePermission(){boolean valid=permission==epoch;permission=-1;return valid;}
    boolean utterance(String id,long token){return token==epoch&&("voodoo-"+token).equals(id);}
}
