package com.ether4o4.voodoo;
import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PinStoreTest {
    SharedPreferences preferences(Map<String,Object> values){
        Object[] editor=new Object[1];editor[0]=Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.Editor.class},(p,m,a)->{
            if(m.getName().equals("putString")){values.put((String)a[0],a[1]);return editor[0];}
            if(m.getName().equals("commit"))return true;return null;
        });
        return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.class},(p,m,a)->{
            if(m.getName().equals("getString"))return values.getOrDefault(a[0],a[1]);if(m.getName().equals("edit"))return editor[0];return null;
        });
    }
    @Test public void favoritesAndSavedPersistWithoutChangingExistingIds()throws Exception{
        Map<String,Object> values=new HashMap<>();PinStore store=new PinStore(preferences(values));
        String favorite=store.save("https://example.com/favorite","Favorite","favorites",null).getString("id");
        String saved=store.save("https://example.com/saved","Saved","gold",null).getString("id");
        PinStore.validate(store.all());PinStore restored=new PinStore(preferences(values));
        assertEquals("favorites",restored.find(favorite).getString("group"));assertEquals("gold",restored.find(saved).getString("group"));
        restored.update(new JSONObject().put("id",favorite).put("title","Renamed"));assertEquals("Renamed",new PinStore(preferences(values)).find(favorite).getString("title"));
        assertEquals(2,restored.all().length());
    }
    @Test public void customNamesPersistAndFavoritesIsFixed()throws Exception{
        JSONObject names=PinStore.workspaceNames(new JSONObject().put("gold","  Archive  "));assertEquals("Archive",names.getString("gold"));assertEquals("Favorites",names.getString("favorites"));
        Map<String,Object> values=new HashMap<>();values.put("options",new JSONObject().put("workspaceNames",names).toString());PinStore store=new PinStore(preferences(values));
        assertEquals("Archive",store.name("gold"));assertEquals("Favorites",store.name("favorites"));
        try{PinStore.workspaceNames(new JSONObject().put("favorites","Changed"));fail();}catch(Exception expected){}
        try{PinStore.workspaceNames(new JSONObject().put("gold"," "));fail();}catch(Exception expected){}
    }
    @Test public void sitePolicyUsesTheSameCanonicalHostForSaveAndMatch(){assertEquals("example.com",ScriptRules.canonicalHost("EXAMPLE.com."));assertEquals(ScriptRules.canonicalHost("EXAMPLE.com"),ScriptRules.canonicalHost("example.com"));}
}
