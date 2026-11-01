package com.ether4o4.voodoo;

import android.content.SharedPreferences;

/** Toolbar choices are independent of page state and saved browser settings. */
final class NavigationPreference {
    private final SharedPreferences preferences;
    NavigationPreference(SharedPreferences preferences) { this.preferences = preferences; }
    private String key(boolean landscape) { return landscape ? "navigation_hidden_landscape" : "navigation_hidden_portrait"; }
    boolean hidden(boolean landscape) { return preferences.getBoolean(key(landscape), false); }
    void toggle(boolean landscape) { preferences.edit().putBoolean(key(landscape), !hidden(landscape)).apply(); }
}
