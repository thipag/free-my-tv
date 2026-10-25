package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Image-first catalogs from Cinemeta; each item opens its IMDb ID directly in Nuvio. */
final class CatalogData {
    private static final String MOVIES = "https://v3-cinemeta.strem.io/catalog/movie/top.json";
    private static final String SERIES = "https://v3-cinemeta.strem.io/catalog/series/top.json";
    private static final long REFRESH_MS = 60 * 60_000L;
    private static final int CACHE_VERSION = 2;
    private static final int MAX_ITEMS = 30;
    private static CatalogData instance;

    static synchronized CatalogData get(Context c) {
        if (instance == null) instance = new CatalogData(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final File imageDir;
    // Feed refreshes and poster downloads share this queue. A small pool lets a page's
    // posters arrive together instead of leaving later cards blank while one request stalls.
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Recs.Pick> movies = new ArrayList<>(), series = new ArrayList<>();
    private final Map<Integer, Bitmap> images = new HashMap<>();
    private final Set<String> pending = new HashSet<>();
    private final long[] updated = new long[2];
    private final boolean[] loading = new boolean[2];
    private volatile Runnable changed;

    private CatalogData(Context c) {
        context = c;
        cache = c.getSharedPreferences("cinemeta-catalog", Context.MODE_PRIVATE);
        imageDir = new File(c.getCacheDir(), "cinemeta-images");
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        load(0, movies);
        load(1, series);
    }

    synchronized List<Recs.Pick> get(int type, Runnable callback) {
        if (type < 0 || type > 1) return new ArrayList<>();
        changed = callback;
        List<Recs.Pick> out = type == 0 ? movies : series;
        if (out.isEmpty() || System.currentTimeMillis() - updated[type] > REFRESH_MS) fetch(type);
        List<Recs.Pick> varied = new ArrayList<>(out);
        if (varied.size() > 1) {
            int offset = (int) ((System.currentTimeMillis() / REFRESH_MS + type * 7L) % varied.size());
            if (offset > 0) {
                List<Recs.Pick> rotated = new ArrayList<>(varied.size());
                rotated.addAll(varied.subList(offset, varied.size()));
                rotated.addAll(varied.subList(0, offset));
                varied = rotated;
            }
        }
        return varied;
    }

    /** Load artwork for the cards on the page the viewer is looking at (later pages are queued on demand). */
    synchronized void prefetch(List<Recs.Pick> visible) {
        queueImages(visible);
    }

    synchronized Bitmap image(Recs.Pick pick) {
        return pick == null ? null : images.get(pick.id);
    }

    private void load(int type, List<Recs.Pick> into) {
        updated[type] = cache.getLong("updated:" + type, 0);
        try {
            JSONArray rows = new JSONArray(cache.getString("items:" + type, "[]"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.getJSONObject(i);
                Recs.Pick p = new Recs.Pick();
                p.id = o.optInt("id"); p.kind = "title"; p.pkg = "com.nuvio.tv";
                p.title = o.optString("title"); p.meta = o.optString("meta");
                p.imageUrl = o.optString("image"); p.link = o.optString("link");
                if (!p.title.isEmpty() && !p.imageUrl.isEmpty() && !p.link.isEmpty()) into.add(p);
            }
        } catch (Exception ignored) { }
    }

    private synchronized void fetch(int type) {
        if (loading[type]) return;
        loading[type] = true;
        io.execute(() -> {
            List<Recs.Pick> fresh = new ArrayList<>();
            HttpURLConnection connection = null;
            try {
                String address = type == 0 ? MOVIES : SERIES;
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(7000); connection.setReadTimeout(9000);
                connection.setRequestProperty("User-Agent", "FreeMyTV/1.2 AndroidTV");
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = in.read(buffer)) != -1 && bytes.size() < 1_500_000) bytes.write(buffer, 0, read);
                    JSONArray metas = new JSONObject(bytes.toString("UTF-8")).optJSONArray("metas");
                    if (metas != null) for (int i = 0; i < metas.length() && fresh.size() < MAX_ITEMS; i++) {
                        JSONObject m = metas.optJSONObject(i);
                        if (m == null) continue;
                        String id = m.optString("imdb_id", m.optString("id"));
                        String name = m.optString("name");
                        String poster = m.optString("poster");
                        String mediaType = m.optString("type");
                        if (!mediaType.isEmpty() && !(type == 0 ? "movie" : "series").equalsIgnoreCase(mediaType)) continue;
                        if (!id.matches("tt[0-9]+") || name.isEmpty() || !poster.startsWith("http")) continue;
                        Recs.Pick p = new Recs.Pick();
                        p.id = (type + ":" + id).hashCode(); p.kind = "title"; p.pkg = "com.nuvio.tv";
                        p.title = name; p.meta = m.optString("year");
                        // Cinemeta's poster CDN is intermittently blocked by TV network filters. Metahub's
                        // IMDb keyed endpoint is stable and serves the same poster art directly.
                        p.imageUrl = "https://images.metahub.space/poster/small/" + id + "/img";
                        p.backdrop = m.optString("background");
                        p.link = "nuvio://imdb/" + (type == 0 ? "movie/" : "series/") + id;
                        fresh.add(p);
                    }
                }
            } catch (Exception e) { AppLog.w("catalog feed: " + e.getClass().getSimpleName()); }
            finally { if (connection != null) connection.disconnect(); }
            synchronized (this) {
                List<Recs.Pick> target = type == 0 ? movies : series;
                if (!fresh.isEmpty()) {
                    target.clear(); target.addAll(fresh);
                    updated[type] = System.currentTimeMillis();
                    JSONArray saved = new JSONArray();
                    for (Recs.Pick p : fresh) {
                        JSONObject o = new JSONObject();
                        try { o.put("id", p.id); o.put("title", p.title); o.put("meta", p.meta);
                            o.put("image", p.imageUrl); o.put("link", p.link); saved.put(o); }
                        catch (Exception ignored) { }
                    }
                    cache.edit().putLong("updated:" + type, updated[type]).putString("items:" + type, saved.toString()).apply();
                }
                loading[type] = false;
            }
            Runnable callback = changed;
            if (callback != null) main.post(callback);
        });
    }

    private void queueImages(List<Recs.Pick> list) {
        for (Recs.Pick p : list) {
            synchronized (images) { if (images.containsKey(p.id) || !pending.add(p.imageUrl)) continue; }
            io.execute(() -> {
                Bitmap bitmap = loadImage(p);
                synchronized (images) { pending.remove(p.imageUrl); if (bitmap != null) images.put(p.id, bitmap); }
                Runnable callback = changed;
                if (bitmap != null && callback != null) main.post(callback);
            });
        }
    }

    private Bitmap loadImage(Recs.Pick p) {
        try {
            imageDir.mkdirs();
            File file = new File(imageDir, Integer.toHexString(p.id) + ".img");
            Bitmap cached = decodeImage(file);
            if (cached != null) return cached;
            if (file.exists()) file.delete(); // discard a truncated/invalid cache entry left by an interrupted fetch

            for (int attempt = 0; attempt < 2; attempt++) {
                HttpURLConnection connection = null;
                File partial = new File(imageDir, file.getName() + ".part");
                try {
                    connection = (HttpURLConnection) new URL(p.imageUrl).openConnection();
                    connection.setConnectTimeout(6000); connection.setReadTimeout(8000);
                    connection.setRequestProperty("User-Agent", "FreeMyTV/1.2 AndroidTV");
                    if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) continue;
                    int total = 0;
                    try (InputStream in = connection.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(partial)) {
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            total += n;
                            if (total > 3_000_000) break;
                            out.write(buffer, 0, n);
                        }
                        out.flush();
                    }
                    if (total <= 0 || total > 3_000_000 || !partial.renameTo(file)) {
                        partial.delete();
                        continue;
                    }
                    Bitmap loaded = decodeImage(file);
                    if (loaded != null) return loaded;
                    file.delete();
                } finally {
                    if (connection != null) connection.disconnect();
                    if (partial.exists()) partial.delete();
                }
            }
        } catch (Exception e) {
            AppLog.w("catalog image: " + e.getClass().getSimpleName());
        }
        return null;
    }

    private Bitmap decodeImage(File file) {
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / (options.inSampleSize * 2) >= 1000
                || bounds.outHeight / (options.inSampleSize * 2) >= 700) options.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getPath(), options);
    }
}
