package com.ether4o4.voodoo;

import org.json.*;
import java.util.*;

/** Only execution changes should replace the registrations of a live website. */
final class ScriptChanges {
    private ScriptChanges() {}
    static String mode(JSONObject script) {
        return script.optBoolean("enabled") ? "automatic" :
                "manual".equals(script.optString("activation")) ? "manual" : "saved";
    }
    static JSONObject mode(JSONObject script, String mode) throws JSONException {
        if (!Arrays.asList("saved", "automatic", "manual").contains(mode))
            throw new IllegalArgumentException("Choose a script activation mode.");
        return script.put("activation", mode).put("enabled", "automatic".equals(mode));
    }
    private static Map<String,String> automatic(JSONArray scripts) {
        Map<String,String> result = new HashMap<>();
        if (scripts != null) for (int i=0;i<scripts.length();i++) {
            JSONObject script=scripts.optJSONObject(i);
            if (script != null && script.optBoolean("enabled"))
                result.put(script.optString("id"), script.optString("code"));
        }
        return result;
    }
    static boolean automaticChanged(JSONArray before, JSONArray after) {
        return !automatic(before).equals(automatic(after));
    }
    static JSONObject find(JSONArray scripts, String id) {
        if (scripts != null) for (int i=0;i<scripts.length();i++) {
            JSONObject script=scripts.optJSONObject(i);
            if (script != null && id.equals(script.optString("id"))) return script;
        }
        return null;
    }
    static boolean manualChanged(JSONObject running, JSONArray before, JSONArray after) {
        if (running == null || find(before,running.optString("id")) == null) return false;
        JSONObject next=find(after,running.optString("id"));
        return next == null || !running.optString("code").equals(next.optString("code")) ||
                !mode(find(before,running.optString("id"))).equals(mode(next));
    }
}
