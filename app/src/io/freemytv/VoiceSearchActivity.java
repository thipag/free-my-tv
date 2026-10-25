package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Compact, source-grouped Android TV search. The Google recognizer supplies transcription only. */
public final class VoiceSearchActivity extends Activity {
    private static final int VOICE_REQUEST = 418;
    private static final int SEARCH = 0, MIC = 1;
    private static final ExecutorService IMAGES = Executors.newFixedThreadPool(3);
    private EditText queryField;
    private TextView status;
    private LinearLayout results;
    private IconAction micButton;
    private IconAction searchButton;
    private int generation;

    static void open(Context context) { open(context, true); }

    static void openSearch(Context context) { open(context, false); }

    private static void open(Context context, boolean voice) {
        context.startActivity(new Intent(context, VoiceSearchActivity.class)
                .putExtra("voice", voice)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        buildUi();
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        if (getIntent().getBooleanExtra("voice", false)) getWindow().getDecorView().post(() -> {
            micButton.requestFocus();
            startVoice();
        });
        else queryField.requestFocus();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF3F6FA);
        root.setPadding(dp(46), dp(24), dp(46), dp(20));

        TextView heading = new TextView(this);
        heading.setText(Ui.tr("Search"));
        heading.setTextColor(0xFF172033);
        heading.setTextSize(29);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(48)));

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(20), 0, dp(8), 0);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(Color.WHITE); searchBg.setCornerRadius(dp(24)); searchBg.setStroke(dp(1), 0xFFD6DEE8);
        searchRow.setBackground(searchBg);
        queryField = new EditText(this);
        queryField.setSingleLine(true); queryField.setTextSize(22); queryField.setTextColor(0xFF172033);
        queryField.setHintTextColor(0xFF7A8797); queryField.setHint("Filme, série, vídeo ou música");
        queryField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        queryField.setBackgroundColor(Color.TRANSPARENT);
        queryField.setSelectAllOnFocus(false);
        searchRow.addView(queryField, new LinearLayout.LayoutParams(0, dp(64), 1));
        micButton = new IconAction(this, MIC, Ui.tr("Voice search"));
        micButton.setOnClickListener(v -> startVoice());
        searchRow.addView(micButton, new LinearLayout.LayoutParams(dp(54), dp(54)));
        searchButton = new IconAction(this, SEARCH, Ui.tr("Search"));
        searchButton.setOnClickListener(v -> search(queryField.getText().toString()));
        searchRow.addView(searchButton, new LinearLayout.LayoutParams(dp(54), dp(54)));
        root.addView(searchRow, new LinearLayout.LayoutParams(-1, dp(72)));
        queryField.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                search(queryField.getText().toString()); return true;
            }
            return false;
        });

        status = new TextView(this);
        status.setTextColor(0xFF667487); status.setTextSize(17);
        status.setPadding(dp(6), dp(12), dp(6), dp(8));
        status.setText("Digite ou dite para pesquisar nos apps compatíveis.");
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(48)));

        ScrollView vertical = new ScrollView(this);
        vertical.setFillViewport(false);
        vertical.setClipToPadding(false);
        vertical.setVerticalScrollBarEnabled(false);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(2), 0, dp(28));
        vertical.addView(results, new ScrollView.LayoutParams(-1, -2));
        root.addView(vertical, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void startVoice() {
        try {
            hideKeyboard();
            micButton.requestFocus();
            Intent voice = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            voice.setPackage("com.google.android.katniss");
            voice.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            voice.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR");
            voice.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR");
            voice.putExtra(RecognizerIntent.EXTRA_PROMPT, "Fale agora");
            voice.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            if (voice.resolveActivity(getPackageManager()) == null) {
                status.setText("Busca por voz indisponível. Digite para pesquisar.");
                queryField.requestFocus();
                return;
            }
            startActivityForResult(voice, VOICE_REQUEST);
        } catch (RuntimeException e) {
            status.setText("Busca por voz indisponível. Digite para pesquisar.");
            AppLog.w("voice recognizer: " + e.getClass().getSimpleName());
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE_REQUEST) return;
        ArrayList<String> phrases = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (result == RESULT_OK && phrases != null && !phrases.isEmpty()) {
            queryField.setText(phrases.get(0));
            hideKeyboard();
            search(phrases.get(0));
        } else {
            hideKeyboard();
            status.setText("Não consegui entender. Tente novamente ou digite o título.");
            micButton.requestFocus();
        }
    }

    private void search(String raw) {
        String query = raw == null ? "" : raw.trim();
        if (query.length() < 2) {
            status.setText("Digite ou dite pelo menos dois caracteres.");
            queryField.requestFocus();
            return;
        }
        queryField.setText(query);
        hideKeyboard();
        queryField.clearFocus();
        status.setText("Pesquisando nos apps disponíveis…");
        results.removeAllViews();
        final int request = ++generation;
        UnifiedSearch.search(this, query, sections -> runOnUiThread(() -> {
            if (request != generation || isFinishing()) return;
            boolean found = false;
            View firstResult = null;
            for (UnifiedSearch.Section section : sections) {
                if (section.hits.isEmpty()) continue;
                View firstInSection = addSection(section);
                if (firstResult == null) firstResult = firstInSection;
                found = true;
            }
            status.setText(found ? "Resultados para “" + query + "”" : "Nenhum resultado nos apps compatíveis para “" + query + "”.");
            if (firstResult != null) firstResult.requestFocus();
            else micButton.requestFocus();
        }));
    }

    private View addSection(UnifiedSearch.Section section) {
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(4), dp(18), 0, dp(8));
        ImageView appIcon = new ImageView(this);
        try { appIcon.setImageDrawable(getPackageManager().getApplicationIcon(section.packageName)); }
        catch (Exception ignored) { }
        heading.addView(appIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView label = new TextView(this);
        label.setText(section.name); label.setTextColor(0xFF263346); label.setTextSize(21);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setPadding(dp(10), 0, 0, 0);
        heading.addView(label, new LinearLayout.LayoutParams(-2, -2));
        TextView arrows = new TextView(this);
        arrows.setText("‹     ›"); arrows.setTextColor(0xFF6F8DAB); arrows.setTextSize(22);
        arrows.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        arrows.setPadding(0, 0, dp(8), 0);
        arrows.setVisibility(section.hits.size() > 3 ? View.VISIBLE : View.INVISIBLE);
        heading.addView(arrows, new LinearLayout.LayoutParams(0, -1, 1));
        results.addView(heading, new LinearLayout.LayoutParams(-1, dp(58)));

        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false); horizontal.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setPadding(dp(3), dp(8), dp(28), dp(22));
        View first = null;
        for (UnifiedSearch.Hit hit : section.hits) {
            View item = card(hit, section.layout);
            if (first == null) first = item;
            row.addView(item);
        }
        horizontal.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        horizontal.setOnScrollChangeListener((v, scrollX, scrollY, oldX, oldY) -> {
            if (section.hits.size() <= 3) return;
            boolean left = scrollX > dp(4);
            boolean right = scrollX + horizontal.getWidth() < row.getWidth() - dp(4);
            arrows.setText((left ? "‹" : " ") + "     " + (right ? "›" : " "));
        });
        results.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
        return first;
    }

    private View card(UnifiedSearch.Hit hit, int layout) {
        int cardWidth = dp(layout == UnifiedSearch.POSTER ? 150 : layout == UnifiedSearch.SQUARE ? 164 : 270);
        int contentWidth = cardWidth - dp(8);
        int artHeight = dp(layout == UnifiedSearch.POSTER ? 214 : layout == UnifiedSearch.SQUARE ? 164 : 152);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true); card.setClickable(true); card.setElevation(dp(4));
        card.setPadding(dp(4), dp(4), dp(4), dp(4));
        card.setBackground(cardBackground(false));
        card.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(cardBackground(focused)); v.setElevation(dp(focused ? 15 : 4));
            v.animate().scaleX(focused ? 1.045f : 1f).scaleY(focused ? 1.045f : 1f)
                    .translationY(focused ? -dp(8) : 0).setDuration(130).start();
        });
        card.setOnClickListener(v -> { hit.open(this); });

        TopRoundedImageView image = new TopRoundedImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(0xFFDCE5EF);
        card.addView(image, new LinearLayout.LayoutParams(contentWidth, artHeight));
        LinearLayout caption = new LinearLayout(this); caption.setOrientation(LinearLayout.VERTICAL);
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable captionBg = new GradientDrawable();
        captionBg.setColor(Color.WHITE); captionBg.setCornerRadii(new float[] {0, 0, 0, 0, dp(11), dp(11), dp(11), dp(11)});
        caption.setBackground(captionBg);
        TextView title = new TextView(this); title.setText(hit.title); title.setTextColor(0xFF172033);
        title.setTextSize(16); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        caption.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (!TextUtils.isEmpty(hit.detail)) {
            TextView detail = new TextView(this); detail.setText(hit.detail); detail.setTextColor(0xFF687587);
            detail.setTextSize(13); detail.setMaxLines(1); detail.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
            detailLp.topMargin = dp(3);
            caption.addView(detail, detailLp);
        }
        card.addView(caption, new LinearLayout.LayoutParams(contentWidth, dp(58)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, -2);
        lp.rightMargin = dp(14);
        card.setLayoutParams(lp);

        if (hit.imageUri != null) IMAGES.execute(() -> {
            Bitmap bitmap = loadImage(hit.imageUri);
            if (bitmap != null && !isFinishing()) runOnUiThread(() -> image.setImageBitmap(bitmap));
        });
        return card;
    }

    private Bitmap loadImage(Uri uri) {
        try {
            if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) {
                HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
                connection.setConnectTimeout(5000); connection.setReadTimeout(7000);
                connection.setRequestProperty("User-Agent", "FreeMyTV/1.2 AndroidTV");
                try (InputStream stream = connection.getInputStream()) { return BitmapFactory.decodeStream(stream); }
                finally { connection.disconnect(); }
            }
            try (InputStream stream = getContentResolver().openInputStream(uri)) { return BitmapFactory.decodeStream(stream); }
        } catch (Exception ignored) { return null; }
    }

    private GradientDrawable cardBackground(boolean focused) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(focused ? 0xFFFDFEFF : Color.WHITE); d.setCornerRadius(dp(17));
        d.setStroke(dp(focused ? 3 : 1), focused ? 0xFF3978E8 : 0xFFD8E0E9);
        return d;
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && queryField != null) imm.hideSoftInputFromWindow(queryField.getWindowToken(), 0);
        } catch (RuntimeException ignored) { }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private final class IconAction extends View {
        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        IconAction(Context context, int kind, String description) {
            super(context); this.kind = kind;
            setFocusable(true); setClickable(true); setContentDescription(description);
            setOnFocusChangeListener((v, focused) -> invalidate());
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            boolean focused = isFocused();
            paint.setStyle(Paint.Style.FILL); paint.setColor(focused ? 0xFF5D9BD1 : 0xFFE8EFF6);
            canvas.drawCircle(cx, cy, dp(22), paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.4f));
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(focused ? Color.WHITE : 0xFF30445B);
            if (kind == SEARCH) {
                canvas.drawCircle(cx - dp(3), cy - dp(3), dp(7), paint);
                canvas.drawLine(cx + dp(2), cy + dp(2), cx + dp(8), cy + dp(8), paint);
            } else {
                RectF mic = new RectF(cx - dp(5), cy - dp(12), cx + dp(5), cy + dp(4));
                paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(mic, dp(5), dp(5), paint);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawArc(cx - dp(10), cy - dp(6), cx + dp(10), cy + dp(13), 0, 180, false, paint);
                canvas.drawLine(cx, cy + dp(13), cx, cy + dp(18), paint);
                canvas.drawLine(cx - dp(5), cy + dp(18), cx + dp(5), cy + dp(18), paint);
            }
        }
    }

    /** Poster image has square lower corners; only the text base is rounded, so each result reads as one card. */
    private final class TopRoundedImageView extends ImageView {
        private final Path clip = new Path();
        private final RectF bounds = new RectF();
        TopRoundedImageView(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            bounds.set(0, 0, getWidth(), getHeight());
            float r = dp(15);
            clip.reset();
            clip.moveTo(0, r); clip.quadTo(0, 0, r, 0); clip.lineTo(getWidth() - r, 0);
            clip.quadTo(getWidth(), 0, getWidth(), r); clip.lineTo(getWidth(), getHeight());
            clip.lineTo(0, getHeight()); clip.close();
            int save = canvas.save(); canvas.clipPath(clip); super.onDraw(canvas); canvas.restoreToCount(save);
        }
    }
}
