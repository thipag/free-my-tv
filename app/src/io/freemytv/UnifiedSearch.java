package io.freemytv;

import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Queries Android TV's globally searchable apps, plus the Nuvio catalog fallback. */
final class UnifiedSearch {
    private static final String NUVIO = "com.nuvio.tv";
    private static final String SMARTTUBE = "org.smarttube.beta";
    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    static final int POSTER = 1, SQUARE = 2;

    interface Callback { void onResults(List<Section> sections); }

    static final class Section {
        final String name, packageName;
        final int layout;
        final List<Hit> hits;
        Section(String name, String packageName, int layout, List<Hit> hits) {
            this.name = name; this.packageName = packageName; this.layout = layout; this.hits = hits;
        }
    }

    static final class Hit {
        final String title, detail, image, packageName;
        final Uri imageUri;
        final Intent intent;
        final int layout;

        Hit(String title, String detail, String image, String packageName, Uri imageUri, Intent intent, int layout) {
            this.title = title; this.detail = detail; this.image = image; this.packageName = packageName;
            this.imageUri = imageUri; this.intent = intent; this.layout = layout;
        }

        void open(Context context) {
            try {
                Intent launch = new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
            } catch (RuntimeException e) { AppLog.w("search result open: " + e.getClass().getSimpleName()); }
        }
    }

    private static final class Source {
        final SearchableInfo info;
        final String pkg, name;
        Source(SearchableInfo info, String pkg, String name) { this.info = info; this.pkg = pkg; this.name = name; }
    }

    private UnifiedSearch() {}

    static void search(Context context, String query, Callback callback) {
        Context app = context.getApplicationContext();
        List<Source> sources = searchableSources(app);
        boolean nuvioFallback = true;
        for (Source source : sources) if (NUVIO.equals(source.pkg)) { nuvioFallback = false; break; }
        List<Section> sections = Collections.synchronizedList(new ArrayList<>());
        final boolean queryNuvioFallback = nuvioFallback;
        AtomicInteger remaining = new AtomicInteger(sources.size() + (queryNuvioFallback ? 1 : 0));
        Runnable complete = () -> {
            if (remaining.decrementAndGet() == 0) {
                List<Section> ready;
                synchronized (sections) { ready = new ArrayList<>(sections); }
                ready.sort(Comparator.comparingInt(s -> sourceOrder(s.packageName)));
                callback.onResults(ready);
            }
        };
        for (Source source : sources) IO.execute(() -> {
            try {
                List<Hit> hits = source.info == null ? searchSmartTube(app, source.pkg, query)
                        : querySource(app, source, query);
                if (hits.isEmpty() && SMARTTUBE.equals(source.pkg) && source.info != null)
                    hits = searchSmartTube(app, source.pkg, query);
                if (!hits.isEmpty()) sections.add(new Section(source.name, source.pkg, layoutFor(source.pkg, hits), hits));
            } catch (Exception e) { AppLog.w("search provider " + source.pkg + ": " + e.getClass().getSimpleName()); }
            finally { complete.run(); }
        });
        if (queryNuvioFallback) IO.execute(() -> {
            try {
                List<Hit> hits = searchNuvio(query);
                if (!hits.isEmpty()) sections.add(new Section("Nuvio", NUVIO, POSTER, hits));
            } catch (Exception e) { AppLog.w("Nuvio catalog search: " + e.getClass().getSimpleName()); }
            finally { complete.run(); }
        });
    }

    /** SearchManager returns only apps opted into the system's Android TV global-search list. */
    private static List<Source> searchableSources(Context context) {
        List<Source> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        SearchManager manager = (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);
        if (manager == null) return out;
        try {
            List<SearchableInfo> searchables = manager.getSearchablesInGlobalSearch();
            PackageManager pm = context.getPackageManager();
            if (searchables != null) for (SearchableInfo info : searchables) {
                if (info == null || TextUtils.isEmpty(info.getSuggestAuthority())) continue;
                ComponentName activity = info.getSearchActivity();
                if (activity == null) continue;
                String pkg = activity.getPackageName();
                if (TextUtils.isEmpty(pkg) || pkg.equals(context.getPackageName()) || !seen.add(pkg)) continue;
                String name = pkg;
                try {
                    ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
                    name = String.valueOf(pm.getApplicationLabel(app));
                } catch (PackageManager.NameNotFoundException ignored) { }
                out.add(new Source(info, pkg, name));
            }
            // SmartTube has a working TV suggestions provider here even when the global searchable-app list omits it.
            if (seen.add(SMARTTUBE)) {
                String name = "SmartTube";
                try {
                    ApplicationInfo app = pm.getApplicationInfo(SMARTTUBE, 0);
                    name = String.valueOf(pm.getApplicationLabel(app));
                } catch (PackageManager.NameNotFoundException ignored) { }
                out.add(new Source(null, SMARTTUBE, name));
            }
        } catch (RuntimeException e) { AppLog.w("Android TV searchable apps: " + e.getClass().getSimpleName()); }
        return out;
    }

    private static List<Hit> querySource(Context context, Source source, String query) {
        List<Hit> out = new ArrayList<>();
        SearchableInfo info = source.info;
        String authority = info.getSuggestAuthority();
        if (TextUtils.isEmpty(authority)) return out;
        Uri.Builder builder = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority);
        if (!TextUtils.isEmpty(info.getSuggestPath())) builder.appendEncodedPath(info.getSuggestPath());
        builder.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);
        String selection = info.getSuggestSelection();
        String[] args = null;
        if (!TextUtils.isEmpty(selection)) args = new String[] {query};
        else builder.appendPath(query);
        Uri uri = builder.build();
        try (Cursor cursor = context.getContentResolver().query(uri, null, selection, args, null)) {
            if (cursor == null) return out;
            int titleCol = column(cursor, SearchManager.SUGGEST_COLUMN_TEXT_1);
            int detailCol = column(cursor, SearchManager.SUGGEST_COLUMN_TEXT_2);
            int imageCol = column(cursor, SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE);
            int imageIconCol = column(cursor, SearchManager.SUGGEST_COLUMN_ICON_1);
            int actionCol = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_ACTION);
            int dataCol = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA);
            int idCol = column(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
            int queryCol = column(cursor, SearchManager.SUGGEST_COLUMN_QUERY);
            int typeCol = column(cursor, SearchManager.SUGGEST_COLUMN_CONTENT_TYPE);
            while (cursor.moveToNext() && out.size() < 24) {
                String title = value(cursor, titleCol);
                if (TextUtils.isEmpty(title)) continue;
                String data = value(cursor, dataCol);
                if (TextUtils.isEmpty(data)) data = info.getSuggestIntentData();
                String id = value(cursor, idCol);
                Uri intentData = null;
                if (!TextUtils.isEmpty(data)) {
                    intentData = Uri.parse(data);
                    if (!TextUtils.isEmpty(id)) intentData = Uri.withAppendedPath(intentData, id);
                }
                String action = value(cursor, actionCol);
                if (TextUtils.isEmpty(action)) action = info.getSuggestIntentAction();
                if (TextUtils.isEmpty(action)) action = Intent.ACTION_SEARCH;
                Intent intent = new Intent(action);
                if (intentData != null) intent.setData(intentData);
                intent.setComponent(info.getSearchActivity());
                String searchQuery = value(cursor, queryCol);
                intent.putExtra(SearchManager.USER_QUERY, query);
                intent.putExtra(SearchManager.QUERY, TextUtils.isEmpty(searchQuery) ? query : searchQuery);
                String imageText = value(cursor, imageCol);
                if (TextUtils.isEmpty(imageText)) imageText = value(cursor, imageIconCol);
                Uri imageUri = imageText == null || imageText.isEmpty() ? null : Uri.parse(imageText);
                if (imageUri != null && TextUtils.isDigitsOnly(imageText))
                    imageUri = Uri.parse("android.resource://" + source.pkg + "/" + imageText);
                int layout = layoutForType(source.pkg, value(cursor, typeCol));
                out.add(new Hit(title, value(cursor, detailCol), imageText, source.pkg, imageUri, intent, layout));
            }
        } catch (Exception e) { AppLog.w("search query " + source.pkg + ": " + e.getClass().getSimpleName()); }
        return out;
    }

    private static int column(Cursor cursor, String name) {
        int col = cursor.getColumnIndex(name);
        if (col < 0 && SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE.equals(name)) col = cursor.getColumnIndex("suggest_result_card_image");
        return col;
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
                Intent intent = new Intent(Intent.ACTION_VIEW, uri).setPackage(NUVIO).addCategory(Intent.CATEGORY_BROWSABLE);
                out.add(new Hit(title, year, art, NUVIO, Uri.parse(art), intent, POSTER));
                typeCount++;
            }
        }
        return out;
    }

    private static List<Hit> searchSmartTube(Context context, String pkg, String query) {
        List<Hit> out = new ArrayList<>();
        Uri uri = new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(pkg)
                .appendPath("search").appendPath("search_suggest_query").appendPath(query).build();
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null) return out;
            int titleCol = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1);
            int detailCol = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_2);
            int imageCol = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE);
            int idCol = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
            while (cursor.moveToNext() && out.size() < 24) {
                String title = value(cursor, titleCol), id = value(cursor, idCol);
                if (TextUtils.isEmpty(title) || TextUtils.isEmpty(id)) continue;
                Uri data = Uri.parse("content://" + pkg + "/search/" + Uri.encode(id));
                Intent intent = new Intent(Intent.ACTION_VIEW, data).setComponent(new ComponentName(pkg,
                        "com.liskovsoft.leanbackassistant.search.SearchableActivity"));
                String image = value(cursor, imageCol);
                out.add(new Hit(title, value(cursor, detailCol), image, pkg,
                        TextUtils.isEmpty(image) ? null : Uri.parse(image), intent, 0));
            }
        } catch (Exception e) { AppLog.w("SmartTube provider search: " + e.getClass().getSimpleName()); }
        return out;
    }

    private static int layoutFor(String pkg, List<Hit> hits) {
        if (NUVIO.equals(pkg)) return POSTER;
        if (pkg.toLowerCase(java.util.Locale.ROOT).contains("spotify") || pkg.toLowerCase(java.util.Locale.ROOT).contains("music")) return SQUARE;
        return hits.isEmpty() ? 0 : hits.get(0).layout;
    }

    private static int layoutForType(String pkg, String type) {
        if (NUVIO.equals(pkg) || type.contains("movie") || type.contains("tvshow")) return POSTER;
        if (pkg.toLowerCase(java.util.Locale.ROOT).contains("spotify") || type.startsWith("audio/")) return SQUARE;
        return 0;
    }

    private static int sourceOrder(String pkg) {
        if (SMARTTUBE.equals(pkg)) return 0;
        if (NUVIO.equals(pkg)) return 1;
        if (pkg != null && pkg.toLowerCase(java.util.Locale.ROOT).contains("spotify")) return 2;
        return 10;
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
