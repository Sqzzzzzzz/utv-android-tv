package dev.mark.utvremote;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the original position/history keys compatible with previous APKs. */
final class WatchState {
    final SharedPreferences prefs;
    WatchState(SharedPreferences prefs) { this.prefs = prefs; }
    boolean watched(String url) { return prefs.getBoolean("watched:" + url, false); }
    boolean isNew(Show show, String url) { return !watched(url) && prefs.getStringSet(show.key("newEpisodes"), new HashSet<>()).contains(url); }
    void opened(Show show, String url) {
        Set<String> fresh = new HashSet<>(prefs.getStringSet(show.key("newEpisodes"), new HashSet<>()));
        fresh.remove(url);
        prefs.edit().putStringSet(show.key("newEpisodes"), fresh).putBoolean("watched:" + url, false).apply();
    }
    void mark(Show show, String url, boolean watched) {
        Set<String> fresh = new HashSet<>(prefs.getStringSet(show.key("newEpisodes"), new HashSet<>())); fresh.remove(url);
        SharedPreferences.Editor edit = prefs.edit().putBoolean("watched:" + url, watched).putStringSet(show.key("newEpisodes"), fresh);
        if (watched) edit.putLong("position:" + url, 0);
        edit.apply();
    }
    void resetProgress(Show show, String url) {
        if (Show.fromUrl(url) != show) return;
        boolean resume = url.equals(resumeUrl(show));
        SharedPreferences.Editor edit = prefs.edit().remove("position:" + url);
        if (resume || url.equals(prefs.getString(show.key("lastUrl"), "")))
            edit.remove(show.key("lastUrl")).remove(show.key("lastTitle"));
        edit.apply();
    }
    void catalogUpdated(Show show, List<CatalogRepository.Episode> episodes) {
        Set<String> known = new HashSet<>(prefs.getStringSet(show.key("knownEpisodes"), new HashSet<>()));
        boolean initialized = prefs.getBoolean(show.key("knownInitialized"), false);
        if (!initialized) {
            try {
                JSONArray old = new JSONArray(prefs.getString(show.key("catalog"), "[]"));
                for (int i = 0; i < old.length(); i++) {
                    String url = old.getJSONObject(i).optString("u");
                    if (Show.fromUrl(url) == show) known.add(url);
                }
            } catch (Exception ignored) { }
            initialized = !known.isEmpty();
        }
        Set<String> fresh = new HashSet<>(prefs.getStringSet(show.key("newEpisodes"), new HashSet<>()));
        for (CatalogRepository.Episode e : episodes) {
            if (initialized && !known.contains(e.url) && !watched(e.url)) fresh.add(e.url);
            known.add(e.url);
        }
        prefs.edit().putStringSet(show.key("knownEpisodes"), known).putStringSet(show.key("newEpisodes"), fresh)
                .putBoolean(show.key("knownInitialized"), true).apply();
    }
    long duration(String url, String siteDuration) {
        long actual = prefs.getLong("duration:" + url, 0);
        if (actual > 0) return actual;
        try { return Long.parseLong(siteDuration.replaceAll("[^0-9]", "")) * 60000; }
        catch (NumberFormatException ignored) { return 0; }
    }
    int progress(String url, String siteDuration) {
        if (watched(url)) return 1000;
        long duration = duration(url, siteDuration);
        return duration <= 0 ? 0 : (int) Math.min(1000, Math.max(0, prefs.getLong("position:" + url, 0)) * 1000.0 / duration);
    }
    String resumeUrl(Show show) {
        String last = prefs.getString(show.key("lastUrl"), "");
        if (Show.fromUrl(last) != show) return "";
        if (!watched(last)) return last;
        try {
            JSONArray catalog = new JSONArray(prefs.getString(show.key("catalog"), "[]")); boolean found = false;
            for (int i = 0; i < catalog.length(); i++) {
                String url = catalog.getJSONObject(i).getString("u");
                if (found && Show.fromUrl(url) == show && !watched(url)) return url;
                if (url.equals(last)) found = true;
            }
        } catch (Exception ignored) { }
        return "";
    }
    String title(Show show, String url) {
        try {
            JSONArray catalog = new JSONArray(prefs.getString(show.key("catalog"), "[]"));
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject e = catalog.getJSONObject(i);
                if (url.equals(e.optString("u"))) return show.title + " · " + e.getInt("s") + " сезон · " + e.getInt("n") + " серия";
            }
        } catch (Exception ignored) { }
        return prefs.getString(show.key("lastTitle"), show.title);
    }
}
