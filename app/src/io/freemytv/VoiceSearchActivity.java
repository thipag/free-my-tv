package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Voice-first search across the TV's Nuvio launch catalog and SmartTube's searchable video provider. */
public final class VoiceSearchActivity extends Activity {
    private static final int VOICE_REQUEST = 418;
    private static final ExecutorService IMAGES = Executors.newFixedThreadPool(3);
    private EditText queryField;
    private TextView status;
    private LinearLayout results;
    private Button voiceButton;
    private int generation;

    static void open(android.content.Context context) {
        context.startActivity(new Intent(context, VoiceSearchActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        if (getIntent().getBooleanExtra("voice", true)) getWindow().getDecorView().postDelayed(this::startVoice, 250);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F6FA);
        root.setPadding(dp(42), dp(24), dp(42), dp(24));

        TextView heading = new TextView(this);
        heading.setText("Buscar");
        heading.setTextColor(0xFF172033);
        heading.setTextSize(30);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(18), 0, dp(10), 0);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(Color.WHITE); searchBg.setCornerRadius(dp(22)); searchBg.setStroke(dp(1), 0xFFD6DEE8);
        searchRow.setBackground(searchBg);
        queryField = new EditText(this);
        queryField.setSingleLine(true); queryField.setTextSize(23); queryField.setTextColor(0xFF172033);
        queryField.setHintTextColor(0xFF7A8797); queryField.setHint("Nome de filme, série ou vídeo");
        queryField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        queryField.setBackgroundColor(Color.TRANSPARENT);
        searchRow.addView(queryField, new LinearLayout.LayoutParams(0, dp(62), 1));
        voiceButton = button("Falar");
        voiceButton.setOnClickListener(v -> startVoice());
        searchRow.addView(voiceButton, new LinearLayout.LayoutParams(dp(130), dp(52)));
        Button searchButton = button("Buscar");
        searchButton.setOnClickListener(v -> search(queryField.getText().toString()));
        searchRow.addView(searchButton, new LinearLayout.LayoutParams(dp(140), dp(52)));
        root.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(72)));
        queryField.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                search(queryField.getText().toString()); return true;
            }
            return false;
        });

        status = new TextView(this);
        status.setTextColor(0xFF667487); status.setTextSize(19); status.setPadding(dp(8), dp(16), dp(8), dp(8));
        status.setText("Fale um título ou digite para pesquisar.");
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(56)));

        ScrollView vertical = new ScrollView(this);
        vertical.setFillViewport(false);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        vertical.addView(results, new ScrollView.LayoutParams(-1, -2));
        root.addView(vertical, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label); button.setTextSize(17); button.setTextColor(Color.WHITE);
        button.setAllCaps(false); button.setFocusable(true); button.setBackground(tint(0xFF5D9BD1, 0xFF397EB9));
        return button;
    }

    private android.graphics.drawable.StateListDrawable tint(int normal, int focused) {
        android.graphics.drawable.StateListDrawable state = new android.graphics.drawable.StateListDrawable();
        GradientDrawable selected = new GradientDrawable(); selected.setColor(focused); selected.setCornerRadius(dp(18));
        GradientDrawable idle = new GradientDrawable(); idle.setColor(normal); idle.setCornerRadius(dp(18));
        state.addState(new int[] {android.R.attr.state_focused}, selected);
        state.addState(new int[] {}, idle);
        return state;
    }

    private void startVoice() {
        try {
            Intent voice = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            voice.setPackage("com.google.android.katniss");
            voice.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            voice.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
            voice.putExtra(RecognizerIntent.EXTRA_PROMPT, "O que você quer encontrar?");
            voice.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            if (voice.resolveActivity(getPackageManager()) == null) {
                status.setText("Busca por voz indisponível. Digite o título para pesquisar.");
                queryField.requestFocus();
                return;
            }
            startActivityForResult(voice, VOICE_REQUEST);
        } catch (RuntimeException e) {
            status.setText("Busca por voz indisponível. Digite o título para pesquisar.");
            AppLog.w("voice recognizer: " + e.getClass().getSimpleName());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_REQUEST) return;
        ArrayList<String> phrases = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (result == RESULT_OK && phrases != null && !phrases.isEmpty()) {
            queryField.setText(phrases.get(0));
            search(phrases.get(0));
        } else {
            status.setText("Não consegui entender. Fale novamente ou digite o título.");
            voiceButton.requestFocus();
        }
    }

    private void search(String raw) {
        String query = raw == null ? "" : raw.trim();
        if (query.length() < 2) {
            status.setText("Digite ou fale pelo menos dois caracteres.");
            return;
        }
        queryField.setText(query);
        status.setText("Pesquisando em filmes, séries e vídeos…");
        results.removeAllViews();
        final int request = ++generation;
        UnifiedSearch.search(this, query, (nuvio, smartTube, error) -> runOnUiThread(() -> {
            if (request != generation || isFinishing()) return;
            boolean found = !nuvio.isEmpty() || !smartTube.isEmpty();
            status.setText(found ? "Resultados para “" + query + "”" : "Nenhum resultado para “" + query + "”.");
            if (!nuvio.isEmpty()) addSection("Filmes e séries", nuvio);
            if (!smartTube.isEmpty()) addSection("Vídeos", smartTube);
            if (!found) queryField.requestFocus();
        }));
    }

    private void addSection(String title, List<UnifiedSearch.Hit> hits) {
        TextView label = new TextView(this);
        label.setText(title); label.setTextColor(0xFF263346); label.setTextSize(24);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        label.setPadding(dp(8), dp(8), 0, dp(12));
        results.addView(label, new LinearLayout.LayoutParams(-1, dp(52)));

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false); horizontal.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(4), dp(14), dp(16));
        for (UnifiedSearch.Hit hit : hits) row.addView(card(hit));
        horizontal.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        results.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
    }

    private View card(UnifiedSearch.Hit hit) {
        boolean wide = hit.smartTube;
        int cardWidth = dp(wide ? 360 : 240);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true); card.setClickable(true); card.setClipToOutline(true); card.setElevation(dp(5));
        card.setBackground(cardBackground(false));
        card.setPadding(0, 0, 0, dp(10));
        card.setOnFocusChangeListener((v, focused) -> v.setBackground(cardBackground(focused)));
        card.setOnClickListener(v -> { hit.open(this); finish(); });

        FrameLayout artFrame = new FrameLayout(this);
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFFDCE5EF);
        artFrame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        card.addView(artFrame, new LinearLayout.LayoutParams(cardWidth, dp(wide ? 202 : 300)));
        TextView name = new TextView(this); name.setText(hit.title); name.setTextColor(0xFF172033);
        name.setTextSize(18); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        name.setPadding(dp(14), dp(12), dp(14), dp(2));
        card.addView(name, new LinearLayout.LayoutParams(cardWidth, -2));
        TextView meta = new TextView(this); meta.setText(TextUtils.isEmpty(hit.detail) ? hit.source : hit.detail);
        meta.setTextColor(0xFF6B7584); meta.setTextSize(15); meta.setMaxLines(1); meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(dp(14), 0, dp(14), 0);
        card.addView(meta, new LinearLayout.LayoutParams(cardWidth, dp(30)));

        if (!TextUtils.isEmpty(hit.image)) IMAGES.execute(() -> {
            Bitmap bitmap = download(hit.image);
            if (bitmap != null && !isFinishing()) runOnUiThread(() -> image.setImageBitmap(bitmap));
        });
        return card;
    }

    private GradientDrawable cardBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.WHITE); d.setCornerRadius(dp(18));
        d.setStroke(dp(focused ? 3 : 1), focused ? 0xFF5D9BD1 : 0xFFD8E0E9);
        return d;
    }

    private Bitmap download(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(7000);
            connection.setRequestProperty("User-Agent", "FreeMyTV/1.2 AndroidTV");
            try (InputStream stream = connection.getInputStream()) { return BitmapFactory.decodeStream(stream); }
        } catch (Exception ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
