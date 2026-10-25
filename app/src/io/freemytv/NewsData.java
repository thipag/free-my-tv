package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Editorial cards: three image-bearing feeds, cached locally; network and bitmap work stay off the UI thread. */
final class NewsData {
    private static final String BRASIL = "https://agenciabrasil.ebc.com.br/rss/ultimasnoticias/feed.xml";
    private static final String BBC = "https://feeds.bbci.co.uk/portuguese/rss.xml";
    private static final String SCENE = "https://wololo.net/feed/";
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final int CACHE_VERSION = 7;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Pattern HTML_IMAGE = Pattern.compile("<img[^>]+src\\s*=\\s*[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE);
    private static NewsData instance;

    static synchronized NewsData get(Context c) {
        if (instance == null) instance = new NewsData(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final List<Recs.Pick> news = new ArrayList<>();
    private final Map<Integer, Bitmap> images = new HashMap<>();
    private final Map<Integer, Backdrop> backgrounds = new HashMap<>();
    private final File imageDir;
    private long updated;
    private boolean loading, visualsQueued;
    private Runnable changed;

    private NewsData(Context c) {
        context = c;
        cache = c.getSharedPreferences("home-news", Context.MODE_PRIVATE);
        imageDir = new File(c.getCacheDir(), "news-images");
        if (cache.getInt("version", 0) != CACHE_VERSION) {
            cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        }
        updated = cache.getLong("updated", 0);
        try { news.addAll(parseJson(new JSONArray(cache.getString("stories", "[]")))); }
        catch (Exception ignored) { }
    }

    synchronized List<Recs.Pick> get(int tab, Runnable onChanged) {
        changed = onChanged;
        if (!loading && (news.size() < 3 || System.currentTimeMillis() - updated > REFRESH_MS)) fetch();
        List<Recs.Pick> out = new ArrayList<>();
        for (Recs.Pick p : news) {
            boolean include = tab == 3 ? ("Brasil".equals(p.app) || "Mundo".equals(p.app))
                    : tab == 4 && ("PS5".equals(p.app) || "Switch 2".equals(p.app));
            if (include) out.add(p);
        }
        queueVisuals(out);
        return out;
    }

    synchronized Backdrop background(Recs.Pick p) {
        if (p != null && backgrounds.containsKey(p.id)) return backgrounds.get(p.id);
        if (news.size() > 1 && backgrounds.containsKey(news.get(1).id)) return backgrounds.get(news.get(1).id);
        return news.isEmpty() ? null : backgrounds.get(news.get(0).id);
    }

    synchronized Bitmap image(Recs.Pick p) {
        if (p == null) return null;
        Bitmap b = images.get(p.id);
        if (b == null) queueVisuals(new ArrayList<>(news));
        return b;
    }

    private synchronized void fetch() {
        if (loading) return;
        loading = true;
        IO.execute(() -> {
            List<Recs.Pick> next = new ArrayList<>();
            List<Recs.Pick> br = readOrEmpty(BRASIL, "Brasil", 0);
            List<Recs.Pick> world = readOrEmpty(BBC, "Mundo", 1);
            List<Recs.Pick> scene = readOrEmpty(SCENE, "Cena", 2);
            next.addAll(br.subList(0, Math.min(6, br.size())));
            next.addAll(world.subList(0, Math.min(6, world.size())));
            next.addAll(scene.subList(0, Math.min(6, scene.size())));
            synchronized (this) {
                if (!next.isEmpty()) {
                    news.clear(); news.addAll(next);
                    updated = System.currentTimeMillis();
                    cache.edit().putLong("updated", updated).putString("stories", toJson(news).toString()).apply();
                }
            }
            Runnable callback;
            synchronized (this) { loading = false; callback = changed; visualsQueued = false; }
            if (callback != null) MAIN.post(callback);
            queueVisuals(new ArrayList<>(news));
        });
    }

    private static List<Recs.Pick> readOrEmpty(String address, String category, int feed) {
        try { return read(address, category, feed); }
        catch (Exception e) {
            AppLog.w("news feed " + category + ": " + e.getClass().getSimpleName());
            return new ArrayList<>();
        }
    }

    private static List<Recs.Pick> read(String address, String category, int feed) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(7000); c.setReadTimeout(7000); c.setRequestProperty("User-Agent", "FreeMyTV/1.2 TV home");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0 && bytes.size() < 1_500_000) bytes.write(buf, 0, n);
            XmlPullParser x = XmlPullParserFactory.newInstance().newPullParser();
            x.setInput(new ByteArrayInputStream(bytes.toByteArray()), "UTF-8");
            List<Recs.Pick> out = new ArrayList<>();
            String tag = "", title = null, link = null, source = null, image = null;
            boolean inItem = false; long date = 0;
            for (int event = x.getEventType(); event != XmlPullParser.END_DOCUMENT; event = x.next()) {
                if (event == XmlPullParser.START_TAG) {
                    tag = x.getName().toLowerCase(java.util.Locale.ROOT);
                    if (tag.endsWith("item")) { title = link = source = image = null; date = 0; inItem = true; }
                    if (inItem && image == null && (tag.endsWith("thumbnail") || tag.endsWith("content") || tag.endsWith("enclosure"))) {
                        image = attribute(x, "url");
                    }
                } else if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) && inItem) {
                    String value = x.getText().trim();
                    if (tag.endsWith("title") && title == null) title = value;
                    else if (tag.endsWith("link") && link == null) link = value;
                    else if (tag.endsWith("source")) source = value;
                    else if (tag.endsWith("pubdate")) date = parseDate(value);
                    else if (image == null && (tag.endsWith("encoded") || tag.endsWith("description"))) image = htmlImage(value);
                } else if (event == XmlPullParser.END_TAG && x.getName().toLowerCase(java.util.Locale.ROOT).endsWith("item")) {
                    if (title != null && link != null) {
                        String lower = title.toLowerCase(java.util.Locale.ROOT);
                        boolean sceneStory = feed != 2 || lower.contains("ps5") || lower.contains("playstation 5") || lower.contains("switch 2");
                        boolean worldStory = feed != 1 || !(lower.contains("lula") || lower.contains("flávio") || lower.contains("flavio")
                                || lower.contains("brasil") || lower.contains("brasileir") || lower.contains("tse") || lower.contains("eleição")
                                || lower.contains("eleicao") || lower.contains("eleições") || lower.contains("eleicoes")
                                || lower.contains("segundo turno") || lower.contains("são paulo") || lower.contains("presidente")
                                || lower.contains("campanha") || lower.contains("votar") || lower.contains("eleitor") || lower.contains("urna")
                                || lower.contains("pesquisas para") || lower.contains("clique aqui") || lower.contains("clique para")
                                || lower.contains("inscrever") || lower.contains("subscribe") || lower.contains("mst")
                                || lower.contains("rio grande do sul") || lower.contains("batalha política") || lower.contains("batalha politica"));
                        if (sceneStory && worldStory) {
                            if (!validImage(image) && feed == 0 && !link.isEmpty()) image = articleImage(clean(link));
                            if (validImage(image) && !link.isEmpty()) {
                                Recs.Pick p = new Recs.Pick();
                                p.id = (link + title).hashCode(); p.kind = "news"; p.title = clean(title);
                                p.link = clean(link); p.imageUrl = upgradeImage(clean(image)); p.backdrop = Long.toString(date);
                                String topic = feed == 0 ? "Brasil" : feed == 1 ? "Mundo"
                                        : lower.contains("switch 2") ? "Switch 2" : "PS5";
                                p.app = topic; p.meta = topic + " · " + clean(source == null || source.isEmpty() ? (feed == 0 ? "Agência Brasil" : feed == 1 ? "BBC News Brasil" : "Wololo") : source);
                                out.add(p);
                                if (out.size() >= 12) break;
                            }
                        }
                    }
                    inItem = false;
                }
            }
            return out;
        } finally { c.disconnect(); }
    }

    private static String attribute(XmlPullParser x, String name) {
        String value = x.getAttributeValue(null, name);
        if (value == null) for (int i = 0; i < x.getAttributeCount(); i++) if (name.equalsIgnoreCase(x.getAttributeName(i))) return x.getAttributeValue(i);
        return value;
    }

    private static String htmlImage(String html) {
        Matcher m = HTML_IMAGE.matcher(html);
        while (m.find()) {
            String s = clean(m.group(1));
            if (validImage(s)) return s;
        }
        return null;
    }

    private static boolean validImage(String url) {
        if (url == null) return false;
        String s = url.toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("http") && !s.contains("logo") && !s.endsWith(".svg")
                && !s.contains("/ebc.png") && !s.contains("/ebc.gif") && !s.contains("1px");
    }

    private static String articleImage(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(3500); c.setReadTimeout(3500);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FreeMyTV/1.2)");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0 && out.size() < 500_000) out.write(buf, 0, n);
            }
            String html = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("<meta[^>]+(?:property|name)=[\\\"'](?:og:image|twitter:image)[\\\"'][^>]+content=[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find() && validImage(m.group(1))) return clean(m.group(1));
        } catch (Exception ignored) { }
        finally { if (c != null) c.disconnect(); }
        return null;
    }

    private static String upgradeImage(String url) {
        return url.replace("?w=200", "?w=1200").replace("/240/", "/1200/");
    }

    private synchronized void queueVisuals(List<Recs.Pick> items) {
        if (visualsQueued || news.isEmpty()) return;
        visualsQueued = true;
        List<Recs.Pick> copy = new ArrayList<>(items);
        IO.execute(() -> {
            for (int i = 0; i < copy.size(); i++) {
                Recs.Pick p = copy.get(i);
                try {
                    Bitmap bitmap = loadImage(p);
                    if (bitmap != null) synchronized (this) { images.put(p.id, bitmap); }
                    if (i == 1 && bitmap != null) {
                        Bitmap src = BitmapFactory.decodeFile(imageFile(p).getPath());
                        if (src != null) {
                            Backdrop back = Backdrop.make(src, true);
                            synchronized (this) { backgrounds.put(p.id, back); }
                        }
                    }
                } catch (Exception e) { AppLog.w("news image: " + e.getMessage()); }
                Runnable callback;
                synchronized (this) { callback = changed; }
                if (callback != null) MAIN.post(callback);
            }
            synchronized (this) { visualsQueued = false; }
        });
    }

    private Bitmap loadImage(Recs.Pick p) throws Exception {
        imageDir.mkdirs();
        File f = imageFile(p);
        if (!f.isFile() && p.imageUrl != null) {
            HttpURLConnection c = (HttpURLConnection) new URL(p.imageUrl).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(6000); c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FreeMyTV/1.2)");
            try (InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[8192]; int n, total = 0;
                while ((n = in.read(buf)) > 0 && total < 2_000_000) { out.write(buf, 0, n); total += n; }
            } finally { c.disconnect(); }
        }
        if (!f.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1200 || bounds.outHeight / (opts.inSampleSize * 2) >= 800) opts.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    private File imageFile(Recs.Pick p) { return new File(imageDir, Integer.toHexString(p.id) + ".img"); }
    private static String clean(String s) { return s == null ? "" : s.replace("&amp;", "&").replaceAll("\\s+", " ").trim(); }
    private static long parseDate(String value) {
        for (String pattern : new String[] {"EEE, dd MMM yyyy HH:mm:ss z", "EEE, dd MMM yyyy HH:mm:ss Z"})
            try { return new java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(value).getTime(); } catch (Exception ignored) { }
        return 0;
    }

    private static JSONArray toJson(List<Recs.Pick> list) {
        JSONArray a = new JSONArray();
        for (Recs.Pick p : list) try { a.put(new JSONObject().put("id", p.id).put("title", p.title).put("link", p.link)
                .put("meta", p.meta).put("app", p.app).put("date", p.backdrop).put("image", p.imageUrl)); } catch (Exception ignored) { }
        return a;
    }
    private static List<Recs.Pick> parseJson(JSONArray a) {
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) try {
            JSONObject j = a.getJSONObject(i); Recs.Pick p = new Recs.Pick();
            p.id = j.optInt("id"); p.kind = "news"; p.title = j.optString("title"); p.link = j.optString("link");
            p.meta = j.optString("meta"); p.app = j.optString("app"); p.backdrop = j.optString("date"); p.imageUrl = j.optString("image"); out.add(p);
        } catch (Exception ignored) { }
        return out;
    }
}
