package io.freemytv;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the public Android TV preview channels published by Nuvio, SmartTube and Spotify. */
final class NativeFeeds {
    private static final String NUVIO = "com.nuvio.tv";
    private static final String SMARTTUBE = "org.smarttube.beta";
    private static final String SPOTIFY = "com.spotify.tv.android";
    private static final long REFRESH_MS = 60_000L;
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static NativeFeeds instance;

    static synchronized NativeFeeds get(Context context) {
        if (instance == null) instance = new NativeFeeds(context.getApplicationContext());
        return instance;
    }

    private static final class FeedChannel {
        final String pkg, name;
        final long id;
        final List<Recs.Pick> items = new ArrayList<>();
        FeedChannel(String pkg, String name, long id) { this.pkg = pkg; this.name = name; this.id = id; }
    }

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences spotifyArtistCache;
    private final SharedPreferences nuvioSeasonCache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService artistIo = Executors.newFixedThreadPool(3);
    private final ExecutorService seasonIo = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Bitmap> images = new HashMap<>();
    private final Set<String> imageLoads = new HashSet<>();
    private final Set<String> artistLoads = new HashSet<>();
    private final Set<String> seasonLoads = new HashSet<>();
    private final Map<String, Long> artistRetryAfter = new HashMap<>();
    private volatile List<FeedChannel> channels = Collections.emptyList();
    private volatile long updated;
    private volatile boolean loading;
    private boolean refreshRequested;
    private volatile Runnable changed;
    private volatile String error = "";

    private NativeFeeds(Context context) {
        this.context = context;
        resolver = context.getContentResolver();
        spotifyArtistCache = context.getSharedPreferences("spotify-artists", Context.MODE_PRIVATE);
        nuvioSeasonCache = context.getSharedPreferences("nuvio-season-counts", Context.MODE_PRIVATE);
    }

    synchronized List<Recs.Pick> get(int tab, Runnable onChanged) {
        changed = onChanged;
        if (!loading && (updated == 0 || System.currentTimeMillis() - updated > REFRESH_MS)) refresh();
        List<Recs.Pick> out = tab == 1 ? nuvio() : tab == 2 ? smartTube() : Collections.emptyList();
        queueImages(out);
        return out;
    }

    synchronized List<Recs.Pick> home(Runnable onChanged) {
        changed = onChanged;
        if (!loading && (updated == 0 || System.currentTimeMillis() - updated > REFRESH_MS)) refresh();
        List<Recs.Pick> out = new ArrayList<>();
        List<Recs.Pick> n = nuvio(), s = smartTube();
        out.addAll(n);
        out.addAll(s);
        queueImages(out);
        return out;
    }

    synchronized List<Recs.Pick> nuvioHome(Runnable onChanged) {
        changed = onChanged;
        if (!loading && (updated == 0 || System.currentTimeMillis() - updated > REFRESH_MS)) refresh();
        List<Recs.Pick> out = nuvio();
        queueNuvioSeasonCounts(out);
        queueImages(out);
        return out;
    }

    synchronized void refreshNow(Runnable onChanged) {
        changed = onChanged;
        updated = 0;
        if (loading) refreshRequested = true;
        else refresh();
    }

    synchronized List<Recs.Pick> smartTubeHome() {
        if (!loading && (updated == 0 || System.currentTimeMillis() - updated > REFRESH_MS)) refresh();
        List<Recs.Pick> out = smartTube();
        queueImages(out);
        return out;
    }

    synchronized List<Recs.Pick> smartTubeContinue() {
        if (!loading && (updated == 0 || System.currentTimeMillis() - updated > REFRESH_MS)) refresh();
        List<FeedChannel> rows = new ArrayList<>();
        for (FeedChannel f : channels) if (SMARTTUBE.equals(f.pkg)
                && contains(f.name, "continue", "history", "recent", "watch again")) rows.add(f);
        rows.sort(Comparator.comparingInt(f -> contains(f.name, "continue") ? 0
                : contains(f.name, "history", "recent") ? 1 : 2));
        List<Recs.Pick> out = take(rows, 8);
        queueImages(out);
        return out;
    }

    synchronized List<Recs.Pick> spotifyHome(Runnable onChanged) {
        changed = onChanged;
        if (!loading && (updated == 0 || System.currentTimeMillis() - updated > REFRESH_MS)) refresh();
        List<FeedChannel> rows = new ArrayList<>();
        for (FeedChannel f : channels) if (SPOTIFY.equals(f.pkg)) rows.add(f);
        rows.sort(Comparator.comparingInt(f -> contains(f.name, "recent", "history", "continue", "listen again") ? 0
                : contains(f.name, "made for you", "recommend") ? 1
                : contains(f.name, "music", "home") ? 2 : 3));
        List<Recs.Pick> out = take(rows, 16);
        if (out.isEmpty()) {
            Recs.Pick shortcut = new Recs.Pick();
            shortcut.id = -73106;
            shortcut.kind = "spotify_open";
            shortcut.title = Ui.tr("Open Spotify");
            shortcut.meta = Ui.tr("Listen to your music and podcasts");
            shortcut.pkg = SPOTIFY;
            shortcut.app = "Spotify";
            out.add(shortcut);
        }
        queueSpotifyArtists(out);
        queueImages(out);
        return out;
    }

    void openSpotify() {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(SPOTIFY);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }

    synchronized List<Recs.Pick> smartTubeNews(Runnable onChanged) {
        changed = onChanged;
        if (!loading && (updated == 0 || System.currentTimeMillis() - updated > REFRESH_MS)) refresh();
        List<Recs.Pick> out = new ArrayList<>();
        for (FeedChannel f : channels) {
            if (!SMARTTUBE.equals(f.pkg)) continue;
            boolean newsChannel = contains(f.name, "news", "notícias", "noticias", "jornal");
            for (Recs.Pick p : f.items) {
                if (out.size() >= 8) break;
                if (newsChannel) out.add(p);
            }
            if (out.size() >= 8) break;
        }
        if (out.isEmpty()) {
            Recs.Pick shortcut = new Recs.Pick();
            shortcut.id = -73105;
            shortcut.kind = "smarttube_news";
            shortcut.title = Ui.tr("News");
            shortcut.meta = "SmartTube";
            shortcut.pkg = SMARTTUBE;
            shortcut.app = "SmartTube";
            out.add(shortcut);
        }
        queueImages(out);
        return out;
    }

    String error() { return error; }

    Bitmap image(Recs.Pick pick) {
        if (pick == null) return null;
        synchronized (images) { return images.get(pick.id); }
    }

    void open(Recs.Pick pick) {
        if (pick == null) return;
        try {
            Intent intent = null;
            if (!TextUtils.isEmpty(pick.intentUri)) {
                intent = Intent.parseUri(pick.intentUri, Intent.URI_INTENT_SCHEME);
                String actual = intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
                if (!pick.pkg.equals(actual)) intent = null;
            }
            if (intent == null && !TextUtils.isEmpty(pick.link)) {
                intent = new Intent(Intent.ACTION_VIEW, Uri.parse(pick.link)).setPackage(pick.pkg);
            }
            if (intent == null || intent.resolveActivity(context.getPackageManager()) == null) {
                intent = context.getPackageManager().getLaunchIntentForPackage(pick.pkg);
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception e) {
            AppLog.w("native feed open: " + e.getClass().getSimpleName());
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pick.pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
            }
        }
    }

    void openTitle(Recs.Pick pick) {
        if (pick == null || TextUtils.isEmpty(pick.link)) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(pick.link))
                    .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(NUVIO)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(context.getPackageManager()) != null) context.startActivity(intent);
            else context.startActivity(context.getPackageManager().getLaunchIntentForPackage(NUVIO));
        } catch (Exception e) {
            AppLog.w("nuvio title open: " + e.getClass().getSimpleName());
        }
    }

    void openSmartTubeNewsScreen() {
        try {
            Intent intent = new Intent().setComponent(new android.content.ComponentName(
                    SMARTTUBE, "com.liskovsoft.smartyoutubetv2.tv.launchers.NewsLauncherActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        } catch (RuntimeException e) {
            AppLog.w("SmartTube News screen: " + e.getClass().getSimpleName());
        }
    }

    void openSmartTube() {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(SMARTTUBE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            main.postDelayed(this::openSmartTubeNewsScreen, 900);
        }
    }

    private synchronized void refresh() {
        if (loading) return;
        loading = true;
        io.execute(() -> {
            List<FeedChannel> next = new ArrayList<>();
            String problem = "";
            try {
                String[] channelColumns = {TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME,
                        TvContract.Channels.COLUMN_DISPLAY_NAME};
                try (Cursor c = resolver.query(TvContract.Channels.CONTENT_URI, channelColumns, null, null, null)) {
                    if (c != null) while (c.moveToNext()) {
                        String pkg = value(c, TvContract.Channels.COLUMN_PACKAGE_NAME);
                        if (!NUVIO.equals(pkg) && !SMARTTUBE.equals(pkg) && !SPOTIFY.equals(pkg)) continue;
                        long id = c.getLong(c.getColumnIndexOrThrow(TvContract.Channels._ID));
                        String label = value(c, TvContract.Channels.COLUMN_DISPLAY_NAME);
                        FeedChannel row = new FeedChannel(pkg, label, id);
                        readPrograms(row);
                        if (!row.items.isEmpty()) next.add(row);
                    }
                }
            } catch (SecurityException e) {
                problem = "Android TV denied preview rows: " + String.valueOf(e.getMessage());
            } catch (Exception e) {
                problem = "preview rows: " + e.getClass().getSimpleName();
            }
            next.sort(Comparator.comparing((FeedChannel f) -> f.pkg).thenComparing(f -> f.name, String.CASE_INSENSITIVE_ORDER));
            channels = next;
            updated = System.currentTimeMillis();
            error = problem;
            loading = false;
            if (!problem.isEmpty()) AppLog.w(problem);
            notifyChanged();
            boolean again;
            synchronized (NativeFeeds.this) {
                again = refreshRequested;
                refreshRequested = false;
            }
            if (again) refresh();
        });
    }

    private void readPrograms(FeedChannel row) {
        String[] columns = {TvContract.PreviewPrograms._ID, TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
                TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, TvContract.PreviewPrograms.COLUMN_LOGO_URI,
                TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
                "last_engagement_time_utc_millis"};
        Uri uri = TvContract.buildPreviewProgramsUriForChannel(row.id);
        try (Cursor c = resolver.query(uri, columns, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext() && row.items.size() < 24) {
                String title = value(c, TvContract.PreviewPrograms.COLUMN_TITLE);
                if (title.isEmpty()) continue;
                Recs.Pick p = new Recs.Pick();
                p.id = (row.pkg + ":" + row.id + ":" + title).hashCode();
                p.kind = "native";
                p.pkg = row.pkg;
                p.app = row.name;
                p.intentUri = value(c, TvContract.PreviewPrograms.COLUMN_INTENT_URI);
                p.imageUrl = first(value(c, TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI),
                        value(c, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI),
                        value(c, TvContract.PreviewPrograms.COLUMN_LOGO_URI));
                String episode = value(c, TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE);
                String shortDescription = value(c, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION);
                boolean spotify = SPOTIFY.equals(row.pkg);
                p.title = title;
                String season = value(c, TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER);
                String number = value(c, TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER);
                String seasonEpisode = (season.isEmpty() ? "" : "T" + season)
                        + (number.isEmpty() ? "" : (season.isEmpty() ? "" : " · ") + "E" + number);
                if (spotify) {
                    String artist = !episode.isEmpty() && !episode.equals(p.title) ? episode
                            : !shortDescription.isEmpty() && !shortDescription.equals(p.title) ? shortDescription : "";
                    p.meta = artist;
                } else p.meta = !seasonEpisode.isEmpty() && !episode.isEmpty() ? seasonEpisode + " · " + episode
                        : !seasonEpisode.isEmpty() ? seasonEpisode
                        : !episode.isEmpty() ? episode : shortDescription;
                long position = number(c, TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS);
                long total = number(c, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS);
                p.lastEngaged = number(c, "last_engagement_time_utc_millis");
                if (total > 0 && position > 0) p.progress = Math.max(0f, Math.min(1f, (float) position / total));
                if (NUVIO.equals(row.pkg) && total >= 300_000L && position > 0 && position < total) {
                    long minutes = Math.max(1, (total - position + 59_999L) / 60_000L);
                    p.extra = Ui.trf("{n} min left", "n", minutes);
                    p.seasonNumber = positiveInt(season);
                    p.episodeNumber = positiveInt(number);
                    p.remainingMillis = total - position;
                }
                if (p.imageUrl != null && !p.imageUrl.isEmpty()) row.items.add(p);
            }
            row.items.sort((a, b) -> Long.compare(b.lastEngaged, a.lastEngaged));
        } catch (Exception e) {
            AppLog.w("preview channel " + row.pkg + ": " + e.getClass().getSimpleName() + " " + String.valueOf(e.getMessage()));
        }
    }

    private void queueNuvioSeasonCounts(List<Recs.Pick> picks) {
        for (Recs.Pick p : picks) {
            if (!NUVIO.equals(p.pkg) || p.seasonNumber <= 0 || p.episodeNumber <= 0) continue;
            String imdb = imdbId(p.intentUri);
            if (imdb.isEmpty()) continue;
            String key = imdb + "_s" + p.seasonNumber;
            int count = nuvioSeasonCache.getInt(key, 0);
            long cachedAt = nuvioSeasonCache.getLong(key + "_at", 0);
            if (count > 0 && System.currentTimeMillis() - cachedAt < 7L * 24 * 60 * 60_000L) {
                setSeasonDetails(p, count);
                continue;
            }
            synchronized (seasonLoads) { if (!seasonLoads.add(key)) continue; }
            seasonIo.execute(() -> {
                try {
                    int found = fetchSeasonEpisodeCount(imdb, p.seasonNumber);
                    if (found > 0) {
                        nuvioSeasonCache.edit().putInt(key, found).putLong(key + "_at", System.currentTimeMillis()).apply();
                        setSeasonDetails(p, found);
                        notifyChanged();
                    }
                } catch (Exception e) {
                    AppLog.w("Nuvio season details: " + e.getClass().getSimpleName());
                } finally {
                    synchronized (seasonLoads) { seasonLoads.remove(key); }
                }
            });
        }
    }

    private void setSeasonDetails(Recs.Pick p, int count) {
        if (count <= 0 || p.episodeNumber <= 0) return;
        int left = Math.max(0, count - p.episodeNumber);
        p.seasonProgress = Math.min(1f, (float) p.episodeNumber / count);
        String episodes = left > 0
                ? Ui.plural("plural|one:{n} episode left|other:{n} episodes left", left)
                : Ui.tr("Season complete");
        String time = p.remainingMillis > 0
                ? Ui.trf("{n} min left", "n", Math.max(1, (p.remainingMillis + 59_999L) / 60_000L)) : "";
        p.extra = time.isEmpty() ? episodes : time + " · " + episodes;
    }

    private int fetchSeasonEpisodeCount(String imdb, int season) throws Exception {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) return 0;
        boolean bearer = key.startsWith("eyJ");
        String findPath = "/find/" + URLEncoder.encode(imdb, "UTF-8") + "?external_source=imdb_id";
        JSONObject found = tmdb(findPath, key, bearer);
        JSONArray shows = found.optJSONArray("tv_results");
        if (shows == null || shows.length() == 0) return 0;
        int showId = shows.optJSONObject(0) == null ? 0 : shows.optJSONObject(0).optInt("id");
        if (showId <= 0) return 0;
        JSONObject details = tmdb("/tv/" + showId + "/season/" + season, key, bearer);
        int count = details.optInt("episode_count");
        JSONArray episodes = details.optJSONArray("episodes");
        return count > 0 ? count : episodes == null ? 0 : episodes.length();
    }

    private JSONObject tmdb(String path, String key, boolean bearer) throws Exception {
        String url = "https://api.themoviedb.org/3" + path
                + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("Accept", "application/json");
        if (bearer) connection.setRequestProperty("Authorization", "Bearer " + key);
        try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            connection.disconnect();
        }
    }

    private String imdbId(String intentUri) {
        if (TextUtils.isEmpty(intentUri)) return "";
        try {
            String id = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).getStringExtra("contentId");
            return id != null && id.matches("tt[0-9]{5,12}") ? id : "";
        } catch (Exception ignored) { return ""; }
    }

    private static int positiveInt(String value) {
        try { int n = Integer.parseInt(value); return n > 0 ? n : 0; }
        catch (NumberFormatException ignored) { return 0; }
    }

    private List<Recs.Pick> nuvio() {
        List<FeedChannel> matches = new ArrayList<>();
        for (FeedChannel f : channels) if (NUVIO.equals(f.pkg)) matches.add(f);
        List<FeedChannel> continuing = new ArrayList<>();
        for (FeedChannel f : matches) {
            if (contains(f.name, "continue", "assist", "watching", "history", "recent")) continuing.add(f);
        }
        if (!continuing.isEmpty()) matches = continuing;
        List<Recs.Pick> out = new ArrayList<>();
        for (FeedChannel f : matches) out.addAll(f.items);
        // Nuvio can publish more than one continue-watching channel. Sort the full Nuvio
        // set by last engagement before applying the row limit, so the most recently
        // watched title cannot be pushed onto a later page by channel ordering.
        out.sort((a, b) -> Long.compare(b.lastEngaged, a.lastEngaged));
        return out.size() > 8 ? new ArrayList<>(out.subList(0, 8)) : out;
    }

    private List<Recs.Pick> smartTube() {
        List<FeedChannel> matches = new ArrayList<>();
        for (FeedChannel f : channels) if (SMARTTUBE.equals(f.pkg)) matches.add(f);
        List<Recs.Pick> out = new ArrayList<>();
        FeedChannel home = firstChannel(matches, "home", "recommend");
        if (home == null || home.items.isEmpty()) {
            for (FeedChannel candidate : matches) {
                if (!contains(candidate.name, "news", "notícia", "noticia", "jornal") && !candidate.items.isEmpty()) {
                    home = candidate;
                    break;
                }
            }
        }
        // Fill the visual row with several real recommendations; include one history tile when available.
        if (home != null) for (Recs.Pick p : home.items) { out.add(p); if (out.size() >= 16) break; }
        // Keep the Home row honest: history is not presented as recommendations or continue watching.
        return out;
    }

    private static FeedChannel firstChannel(List<FeedChannel> rows, String... words) {
        for (FeedChannel f : rows) if (contains(f.name, words)) return f;
        return null;
    }

    private List<Recs.Pick> take(List<FeedChannel> rows, int limit) {
        List<Recs.Pick> out = new ArrayList<>();
        for (FeedChannel row : rows) for (Recs.Pick p : row.items) {
            out.add(p);
            if (out.size() >= limit) return out;
        }
        return out;
    }

    private void queueImages(List<Recs.Pick> picks) {
        for (Recs.Pick p : picks) {
            if (p == null || TextUtils.isEmpty(p.imageUrl)) continue;
            synchronized (images) { if (images.containsKey(p.id) || !imageLoads.add(p.imageUrl)) continue; }
            io.execute(() -> {
                Bitmap bitmap = loadImage(p.imageUrl);
                synchronized (images) {
                    imageLoads.remove(p.imageUrl);
                    if (bitmap != null) images.put(p.id, bitmap);
                }
                if (bitmap != null) notifyChanged();
            });
        }
    }

    private void queueSpotifyArtists(List<Recs.Pick> picks) {
        for (Recs.Pick p : picks) {
            if (p == null || !SPOTIFY.equals(p.pkg) || TextUtils.isEmpty(p.intentUri)) continue;
            String trackId = spotifyTrackId(p.intentUri);
            if (trackId.isEmpty()) continue;
            String cached = spotifyArtistCache.getString(trackId, "");
            if (!TextUtils.isEmpty(cached)) {
                p.meta = cached;
                continue;
            }
            synchronized (artistLoads) {
                Long retryAt = artistRetryAfter.get(trackId);
                if ((retryAt != null && retryAt > System.currentTimeMillis()) || !artistLoads.add(trackId)) continue;
            }
            artistIo.execute(() -> {
                try {
                    String artist = loadSpotifyArtist(trackId);
                    if (!TextUtils.isEmpty(artist)) {
                        spotifyArtistCache.edit().putString(trackId, artist).apply();
                        trimSpotifyArtistCache(trackId);
                        p.meta = artist;
                        notifyChanged();
                    } else synchronized (artistLoads) {
                        artistRetryAfter.put(trackId, System.currentTimeMillis() + 5 * 60_000L);
                    }
                } finally {
                    synchronized (artistLoads) { artistLoads.remove(trackId); }
                }
            });
        }
    }

    private void trimSpotifyArtistCache(String keep) {
        Map<String, ?> entries = spotifyArtistCache.getAll();
        if (entries.size() <= 256) return;
        SharedPreferences.Editor edit = spotifyArtistCache.edit();
        int remaining = entries.size();
        for (String key : entries.keySet()) {
            if (remaining <= 256) break;
            if (!key.equals(keep)) { edit.remove(key); remaining--; }
        }
        edit.apply();
    }

    private static String spotifyTrackId(String intentUri) {
        String marker = "open.spotify.com/track/";
        int start = intentUri.indexOf(marker);
        if (start < 0) return "";
        start += marker.length();
        int end = start;
        while (end < intentUri.length()) {
            char ch = intentUri.charAt(end);
            if (!(Character.isLetterOrDigit(ch) || ch == '-')) break;
            end++;
        }
        String id = intentUri.substring(start, end);
        return id.length() == 22 ? id : "";
    }

    private String loadSpotifyArtist(String trackId) {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            connection = (HttpURLConnection) new URL("https://open.spotify.com/track/" + trackId).openConnection();
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(4500);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FreeMyTV/1.1)");
            in = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read, total = 0;
            while ((read = in.read(chunk)) != -1 && total < 768 * 1024) { bytes.write(chunk, 0, read); total += read; }
            String html = bytes.toString("UTF-8");
            Matcher matcher = Pattern.compile("<meta[^>]+name=[\\\"']music:musician_description[\\\"'][^>]+content=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
            StringBuilder names = new StringBuilder();
            while (matcher.find()) {
                String name = android.text.Html.fromHtml(matcher.group(1), android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
                if (!name.isEmpty() && !name.equalsIgnoreCase("Spotify")) {
                    if (names.length() > 0) names.append(", ");
                    names.append(name);
                }
            }
            return names.toString();
        } catch (Exception e) {
            AppLog.w("Spotify artist metadata: " + e.getClass().getSimpleName());
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (connection != null) connection.disconnect();
        }
    }

    private Bitmap loadImage(String source) {
        InputStream in = null;
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse(source);
            if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                in = resolver.openInputStream(uri);
            } else {
                connection = (HttpURLConnection) new URL(source).openConnection();
                connection.setConnectTimeout(3500);
                connection.setReadTimeout(4500);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "FreeMyTV/1.1 AndroidTV");
                in = connection.getInputStream();
            }
            if (in == null) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read, total = 0;
            while ((read = in.read(chunk)) != -1 && total < MAX_BYTES) { bytes.write(chunk, 0, read); total += read; }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 600) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception e) {
            AppLog.w("preview art: " + e.getClass().getSimpleName());
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (connection != null) connection.disconnect();
        }
    }

    private void notifyChanged() {
        Runnable r = changed;
        if (r != null) main.post(r);
    }

    private static String value(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? "" : c.getString(i);
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    private static String first(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    private static boolean contains(String value, String... fragments) {
        String lower = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        for (String f : fragments) if (lower.contains(f)) return true;
        return false;
    }
}
