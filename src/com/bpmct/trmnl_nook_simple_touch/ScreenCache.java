package com.bpmct.trmnl_nook_simple_touch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Two-screen mode cache: the last two distinct TRMNL screens, kept in memory and
 * on disk, plus which one is selected.
 *
 * Screens are identified by the /api/display response's "filename", which TRMNL
 * formats as {@code plugin-<6 hex id>-<unix time>} (or {@code mashup-...}). The part
 * before the trailing timestamp is stable per screen, while the timestamp changes
 * with every render - the TRMNL firmware's own image cache relies on the same
 * convention (a newer version of a plugin replaces the older one). So a fresh
 * render updates the slot holding the same screen, and swiping just flips the
 * selection between the two slots without touching the network.
 *
 * When a response has no usable filename, callers pass a synthetic id instead
 * (e.g. the position within a double fetch).
 *
 * Thread-safe: written from the fetch task's background thread, read from the UI
 * thread.
 */
final class ScreenCache {

    private static final String TAG = "ScreenCache";
    static final int SLOTS = 2;
    private static final String PREFS = "trmnl_screen_cache";
    private static final String KEY_SELECTED = "selected_id";
    private static final String FILE_PREFIX = "screen_cache_";

    private static ScreenCache instance;

    private final Context context;
    private final String[] ids = new String[SLOTS];
    private final Bitmap[] bitmaps = new Bitmap[SLOTS];
    private final long[] updatedAt = new long[SLOTS];
    private String selectedId;

    static synchronized ScreenCache get(Context context) {
        if (instance == null) {
            instance = new ScreenCache(context.getApplicationContext());
            instance.load();
        }
        return instance;
    }

    private ScreenCache(Context context) {
        this.context = context;
    }

    /**
     * Stable screen id from a response filename: the name without directory,
     * extension and trailing "-<digits>" timestamp. Returns null when there is
     * nothing usable.
     */
    static String screenIdFromFilename(String filename) {
        if (filename == null) return null;
        String s = filename.trim();
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        int dash = s.lastIndexOf('-');
        if (dash > 0 && dash < s.length() - 1 && isDigits(s.substring(dash + 1))) {
            s = s.substring(0, dash);
        }
        return s.length() == 0 ? null : s;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return s.length() > 0;
    }

    /**
     * Store a screen's latest render. Updates the slot with the same id, else an
     * empty slot, else the slot that isn't selected (so a new screen never evicts
     * the one you're looking at). If nothing is selected yet, selects it.
     */
    void put(String id, Bitmap bitmap) {
        if (id == null || bitmap == null) return;
        int slot;
        synchronized (this) {
            slot = indexOf(id);
            if (slot < 0) slot = indexOf(null);
            if (slot < 0) {
                int sel = indexOf(selectedId);
                slot = (sel == 0) ? 1 : 0;
            }
            ids[slot] = id;
            bitmaps[slot] = bitmap;
            updatedAt[slot] = System.currentTimeMillis();
            if (selectedId == null || indexOf(selectedId) < 0) selectedId = id;
            savePrefs(slot);
        }
        // PNG-compressing a full screen takes a while on the NOOK; do it outside the
        // lock so a swipe (UI thread) isn't blocked by a background refresh.
        saveBitmap(slot, bitmap);
    }

    synchronized void select(String id) {
        if (id == null || indexOf(id) < 0) return;
        selectedId = id;
        prefs().edit().putString(KEY_SELECTED, id).commit();
    }

    synchronized String getSelectedId() {
        return selectedId;
    }

    synchronized Bitmap getSelectedBitmap() {
        int i = indexOf(selectedId);
        return i >= 0 ? bitmaps[i] : null;
    }

    /** The id of the cached screen that isn't selected, or null if there is none. */
    synchronized String getOtherId() {
        for (int i = 0; i < SLOTS; i++) {
            if (ids[i] != null && bitmaps[i] != null && !ids[i].equals(selectedId)) return ids[i];
        }
        return null;
    }

    synchronized Bitmap getBitmap(String id) {
        int i = indexOf(id);
        return i >= 0 ? bitmaps[i] : null;
    }

    /** Human-readable summary for logs, e.g. "[plugin-abc123*, plugin-def456]". */
    synchronized String describe() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) sb.append(", ");
            if (ids[i] == null) {
                sb.append("empty");
            } else {
                sb.append(ids[i]);
                if (ids[i].equals(selectedId)) sb.append('*');
                long ageS = (System.currentTimeMillis() - updatedAt[i]) / 1000L;
                sb.append(" (").append(ageS).append("s old)");
            }
        }
        return sb.append(']').toString();
    }

    private int indexOf(String id) {
        for (int i = 0; i < SLOTS; i++) {
            if (id == null ? ids[i] == null : id.equals(ids[i])) return i;
        }
        return -1;
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private File fileFor(int slot) {
        return new File(context.getFilesDir(), FILE_PREFIX + slot + ".png");
    }

    private void savePrefs(int slot) {
        prefs().edit()
                .putString("id_" + slot, ids[slot])
                .putLong("updated_" + slot, updatedAt[slot])
                .putString(KEY_SELECTED, selectedId)
                .commit();
    }

    private void saveBitmap(int slot, Bitmap bitmap) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(fileFor(slot));
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Throwable t) {
            Log.w(TAG, "could not save slot " + slot + ": " + t);
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
    }

    private void load() {
        SharedPreferences p = prefs();
        for (int i = 0; i < SLOTS; i++) {
            String id = p.getString("id_" + i, null);
            File f = fileFor(i);
            if (id == null || !f.exists()) continue;
            try {
                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (b == null) continue;
                ids[i] = id;
                bitmaps[i] = b;
                updatedAt[i] = p.getLong("updated_" + i, 0L);
            } catch (Throwable t) {
                Log.w(TAG, "could not load slot " + i + ": " + t);
            }
        }
        selectedId = p.getString(KEY_SELECTED, null);
        if (indexOf(selectedId) < 0) selectedId = (ids[0] != null) ? ids[0] : ids[1];
    }
}
