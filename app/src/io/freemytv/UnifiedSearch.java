package io.freemytv;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Search adapters for the Nuvio launch catalog and SmartTube's documented Android TV search provider. */
final class UnifiedSearch {
    private static final String NUVIO = "com.nuvio.tv";
    private static final String SMARTTUBE = "org.smarttube.beta";
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);

    interface Callback { void onResults(List<Hit> nuvio, List<Hit> smartTube, String error); }

    static final class Hit {
        final String title, detail, image, source;
        final Uri uri;
        final boolean smartTube;

        Hit(String title, String detail, String image, String source, Uri uri, boolean smartTube) {
            this.title = title; this.detail = detail; this.image = image; this.source = source;
            this.uri = uri; this.smartTube = smartTube;
        }

        void open(Context context) {
            try {
                Intent intent;
                if (smartTube) {
                    intent = new Intent(Intent.ACTION_VIEW, uri).setComponent(new ComponentName(SMARTTUBE,
                            "com.liskovsoft.leanbackassistant.search.SearchableActivity"));
                } else {
                    intent = new Intent(Intent.ACTION_VIEW, uri).setPackage(NUVIO)
                            .addCategory(Intent.CATEGORY_BROWSABLE);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(context.getPackageManager()) != null) context.startActivity(intent);
            } catch (RuntimeException e) { AppLog.w("voice search open: " + e.getClass().getSimpleName()); }
        }
    }

    private UnifiedSearch() {}

    static void search(Context context, String query, Callback callback) {
        Context app = context.getApplicationContext();
        List<Hit> movies = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Hit> videos = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger remaining = new AtomicInteger(2);
        Runnable complete = () -> { if (remaining.decrementAndGet() == 0) callback.onResults(movies, videos, ""); };
        IO.execute(() -> { try { movies.addAll(searchNuvio(query)); } catch (Exception e) { AppLog.w("Nuvio search: " + e.getClass().getSimpleName()); } finally { complete.run(); } });
        IO.execute(() -> { try { videos.addAll(searchSmartTube(app, query)); } catch (Exception e) { AppLog.w("SmartTube search: " + e.getClass().getSimpleName()); } finally { complete.run(); } });
    }

    private static List<Hit> searchNuvio(String query) throws Exception {
        List<Hit> out = new ArrayList<>();
        String encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20");
        for (String type : new String[] {"movie", "series"}) {
            JSONObject response = get("https://v3-cinemeta.strem.io/catalog/" + type + "/top/search=" + encoded + ".json");
            JSONArray metas = response.optJSONArray("metas");
            if (metas == null) continue;
            int typeCount = 0;
            for (int i = 0; i < metas.length() && typeCount < 12; i++) {
                JSONObject meta = metas.optJSONObject(i);
                if (meta == null) continue;
                String id = meta.optString("imdb_id", meta.optString("id"));
                String title = meta.optString("name");
                if (!id.matches("tt[0-9]+") || title.isEmpty()) continue;
                String mediaType = "series".equalsIgnoreCase(meta.optString("type")) ? "series" : "movie";
                String year = meta.optString("releaseInfo");
                if (year.length() > 4) year = year.substring(0, 4);
                String art = "https://images.metahub.space/poster/medium/" + id + "/img";
                Uri uri = Uri.parse("nuvio://imdb/" + mediaType + "/" + id);
                out.add(new Hit(title, year, art, "Nuvio", uri, false));
                typeCount++;
            }
        }
        return out;
    }

    private static List<Hit> searchSmartTube(Context context, String query) {
        List<Hit> out = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri uri = new Uri.Builder().scheme("content").authority(SMARTTUBE).appendPath("search")
                .appendPath("search_suggest_query").appendPath(query).build();
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor == null) return out;
            int titleCol = cursor.getColumnIndex("suggest_text_1");
            int detailCol = cursor.getColumnIndex("suggest_text_2");
            int imageCol = cursor.getColumnIndex("suggest_result_card_image");
            int idCol = cursor.getColumnIndex("suggest_intent_data_id");
            while (cursor.moveToNext() && out.size() < 20) {
                String title = value(cursor, titleCol), id = value(cursor, idCol);
                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(id)) continue;
                Uri open = Uri.parse("content://" + SMARTTUBE + "/search/" + Uri.encode(id));
                out.add(new Hit(title, value(cursor, detailCol), value(cursor, imageCol), "SmartTube", open, true));
            }
        } catch (Exception e) { AppLog.w("SmartTube provider search: " + e.getClass().getSimpleName()); }
        return out;
    }

    private static JSONObject get(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(6000); connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "FreeMyTV/1.2 AndroidTV");
        try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = stream.read(buffer)) != -1 && bytes.size() < 2_000_000) bytes.write(buffer, 0, read);
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    private static String value(Cursor cursor, int column) {
        return column < 0 || cursor.isNull(column) ? "" : cursor.getString(column);
    }
}
