package com.ether4o4.voodoo;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationPreferenceTest {
    private SharedPreferences preferences(Map<String,Object> values) {
        Object[] editor = new Object[1];
        editor[0] = Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (p,m,a) -> {
            if (m.getName().equals("putBoolean")) { values.put((String)a[0], a[1]); return editor[0]; }
            if (m.getName().equals("apply")) return null;
            throw new AssertionError("Unexpected preference mutation: " + m.getName());
        });
        return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{SharedPreferences.class}, (p,m,a) -> {
            if (m.getName().equals("getBoolean")) return values.getOrDefault(a[0], a[1]);
            if (m.getName().equals("edit")) return editor[0];
            throw new AssertionError(m.getName());
        });
    }
    @Test public void defaultsKeepNavigationVisibleInBothOrientations() {
        NavigationPreference state = new NavigationPreference(preferences(new HashMap<>()));
        assertFalse(state.hidden(false)); assertFalse(state.hidden(true));
    }
    @Test public void landscapeChoiceSurvivesRestartWithoutHidingPortrait() {
        Map<String,Object> values = new HashMap<>();
        new NavigationPreference(preferences(values)).toggle(true);
        NavigationPreference restored = new NavigationPreference(preferences(values));
        assertTrue(restored.hidden(true)); assertFalse(restored.hidden(false));
        restored.toggle(true); assertFalse(restored.hidden(true));
    }
    @Test public void independentChoicesDoNotChangeProfileData() {
        Map<String,Object> values = new HashMap<>();
        values.put("options", "existing settings"); values.put("tabs", "existing tabs"); values.put("scripts", "existing scripts");
        NavigationPreference state = new NavigationPreference(preferences(values));
        state.toggle(false); state.toggle(true); state.toggle(false);
        assertFalse(state.hidden(false)); assertTrue(state.hidden(true));
        assertEquals("existing settings", values.get("options"));
        assertEquals("existing tabs", values.get("tabs")); assertEquals("existing scripts", values.get("scripts"));
        assertEquals(5, values.size());
    }
}
