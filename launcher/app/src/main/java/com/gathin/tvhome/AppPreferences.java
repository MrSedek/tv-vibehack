package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages user preferences: dock items order and hidden apps.
 * Falls back to res/values/config.xml defaults when not customized by user.
 */
public class AppPreferences {

    private static final String PREFS_NAME = "tvhome_prefs";
    private static final String KEY_DOCK_APPS = "dock_apps_v2";
    private static final String KEY_HIDDEN_APPS = "hidden_apps_v2";

    private final Context ctx;
    private final SharedPreferences prefs;

    public AppPreferences(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public List<String> getDockApps() {
        String json = prefs.getString(KEY_DOCK_APPS, null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    list.add(arr.getString(i));
                }
                return list;
            } catch (JSONException ignored) {
            }
        }
        // Fallback to config.xml defaults
        String[] defaults = ctx.getResources().getStringArray(R.array.dock_apps);
        return new ArrayList<>(Arrays.asList(defaults));
    }

    public void saveDockApps(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String pkg : list) {
            arr.put(pkg);
        }
        prefs.edit().putString(KEY_DOCK_APPS, arr.toString()).apply();
    }

    public boolean isDocked(String pkg) {
        return getDockApps().contains(pkg);
    }

    public void pinToDock(String pkg, int maxDock) {
        List<String> list = getDockApps();
        if (list.contains(pkg)) return;
        if (list.size() >= maxDock) {
            // Replace the last one or insert at end
            list.add(pkg);
        } else {
            list.add(pkg);
        }
        saveDockApps(list);
    }

    public void unpinFromDock(String pkg) {
        List<String> list = getDockApps();
        if (list.remove(pkg)) {
            saveDockApps(list);
        }
    }

    public boolean moveDockApp(String pkg, int delta) {
        List<String> list = getDockApps();
        int idx = list.indexOf(pkg);
        if (idx < 0) return false;
        int newIdx = idx + delta;
        if (newIdx < 0 || newIdx >= list.size()) return false;
        String item = list.remove(idx);
        list.add(newIdx, item);
        saveDockApps(list);
        return true;
    }

    public Set<String> getHiddenApps() {
        Set<String> set = prefs.getStringSet(KEY_HIDDEN_APPS, null);
        if (set != null) {
            return new HashSet<>(set);
        }
        String[] defaults = ctx.getResources().getStringArray(R.array.hidden_apps);
        return new HashSet<>(Arrays.asList(defaults));
    }

    public void hideApp(String pkg) {
        Set<String> set = getHiddenApps();
        set.add(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN_APPS, set).apply();
        // Also unpin if docked
        unpinFromDock(pkg);
    }

    public void unhideApp(String pkg) {
        Set<String> set = getHiddenApps();
        set.remove(pkg);
        prefs.edit().putStringSet(KEY_HIDDEN_APPS, set).apply();
    }

    public void resetToDefaults() {
        prefs.edit().remove(KEY_DOCK_APPS).remove(KEY_HIDDEN_APPS).apply();
    }
}
