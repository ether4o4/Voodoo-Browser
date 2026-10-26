package com.ether4o4.voodoo;
import java.util.*;

/** Main-thread run-once state, retained only through the requested reload. */
final class ManualScriptRuns<T,S> {
    private final Map<T,S> pending=new HashMap<>();
    private final Set<T> loaded=new HashSet<>();
    void schedule(T tab,S script){loaded.remove(tab);pending.put(tab,script);}
    S get(T tab){return pending.get(tab);}
    boolean finished(T tab){if(!pending.containsKey(tab))return false;loaded.add(tab);return true;}
    boolean navigating(T tab){if(!loaded.remove(tab))return false;pending.remove(tab);return true;}
    void remove(T tab){loaded.remove(tab);pending.remove(tab);}
}
