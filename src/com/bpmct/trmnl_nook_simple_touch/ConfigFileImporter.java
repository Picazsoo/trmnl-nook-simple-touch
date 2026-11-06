package com.bpmct.trmnl_nook_simple_touch;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * Imports device credentials from a plain-text file, so a fresh install (or a
 * reinstall while testing new builds) doesn't need the ID and API key typed in
 * on the e-ink keyboard.
 *
 * Looks for trmnl-config.txt on the SD card or in the NOOK's "My Files":
 *   /sdcard/trmnl-config.txt
 *   /media/My Files/trmnl-config.txt
 *   /media/trmnl-config.txt
 *
 * Format: one key=value per line; blank lines and lines starting with # are ignored.
 *   id=AA:BB:CC:DD:EE:FF      (device MAC / ID; "mac" is accepted too)
 *   api_key=xxxxxxxxxxxx      ("token" / "access_token" accepted too)
 *   api_url=https://...       (optional, for BYOS servers)
 *
 * Only used when no credentials are saved yet, so values entered in the app are
 * never overwritten. The file is left in place for the next reinstall.
 */
final class ConfigFileImporter {

    static final String FILE_NAME = "trmnl-config.txt";

    private static final String[] SEARCH_DIRS = {
            "/sdcard",
            "/media/My Files",
            "/media",
    };

    interface Logger {
        void log(String msg);
    }

    private ConfigFileImporter() {}

    /**
     * If no credentials are saved, import them from the first config file found.
     * Returns true if credentials were imported.
     */
    static boolean importIfNeeded(Context context, Logger log) {
        if (ApiPrefs.hasCredentials(context)) return false;
        for (int i = 0; i < SEARCH_DIRS.length; i++) {
            File f = new File(SEARCH_DIRS[i], FILE_NAME);
            if (!f.isFile()) continue;
            try {
                if (importFrom(context, f, log)) return true;
            } catch (Throwable t) {
                if (log != null) log.log("config file " + f + " unreadable: " + t);
            }
        }
        return false;
    }

    private static boolean importFrom(Context context, File f, Logger log) throws Exception {
        String id = null;
        String token = null;
        String url = null;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim().toLowerCase();
                String value = line.substring(eq + 1).trim();
                if (value.length() == 0) continue;
                if (key.equals("id") || key.equals("mac") || key.equals("device_id")) {
                    id = value;
                } else if (key.equals("api_key") || key.equals("token") || key.equals("access_token")) {
                    token = value;
                } else if (key.equals("api_url") || key.equals("base_url")) {
                    url = value;
                }
            }
        } finally {
            try { r.close(); } catch (Throwable ignored) {}
        }
        if (id == null || token == null) {
            if (log != null) log.log("config file " + f + " is missing id or api_key - ignored");
            return false;
        }
        ApiPrefs.saveCredentials(context, id, token);
        if (url != null) ApiPrefs.saveApiBaseUrl(context, url);
        // Same as saving credentials in the app: leave gift/showcase mode.
        ApiPrefs.setGiftModeEnabled(context, false);
        ApiPrefs.setShowcaseModeEnabled(context, false);
        // Log the ID but never the key.
        if (log != null) log.log("imported credentials from " + f + " (id=" + id
                + (url != null ? ", api_url=" + url : "") + ")");
        return true;
    }
}
