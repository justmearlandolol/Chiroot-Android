package id.my.chiroot;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaController;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionToken;
import androidx.core.content.FileProvider;
import androidx.core.content.res.ResourcesCompat;
import androidx.media3.session.DefaultMediaNotificationProvider;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CHIROOT — native Android music player.
 *
 * Single-Java-file architecture: every screen, every piece of state and every network call
 * lives in this class. The only documented exception is {@link ChirootPlaybackService}, an
 * inner MediaSessionService subclass required by Android's background-playback rules for
 * Media3 (declared as ".MainActivity$ChirootPlaybackService" in AndroidManifest.xml).
 */
@UnstableApi
public class MainActivity extends AppCompatActivity {

    /* =====================================================================
     * 1. CONSTANTS
     * ===================================================================== */

    static final String TAG = "Chiroot";

    // Supabase — injected by build.gradle from GitHub Secrets, never a service_role key.
    static final String SUPABASE_URL = trimSlash(BuildConfig.SUPABASE_URL);
    static final String SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY;
    static final String OAUTH_REDIRECT_URI = BuildConfig.OAUTH_REDIRECT_URI;
    static final String CRATE_BUCKET = "chiroot-crate";

    // Chiroot palette, extracted from the original web app (:root block of Main.html).
    static final int C_PAPER = 0xFFF7F3E9;
    static final int C_PAPER_2 = 0xFFF1E9D6;
    static final int C_INK = 0xFF1A1A1A;
    static final int C_INK_SOFT = 0xFF33323A;
    static final int C_WOOD = 0xFFA66B3A;
    static final int C_WOOD_DARK = 0xFF7A4B26;
    static final int C_WOOD_LIGHT = 0xFFD2A772;
    static final int C_GOLD = 0xFFC9A227;
    static final int C_VINYL = 0xFF1E1E24;
    static final int C_MUTED = 0xFF7B6F5F;
    static final int C_RED = 0xFFD84A3A;
    static final int C_TEAL = 0xFF3A8A6A;
    static final int C_BLUE = 0xFF5B6EE1;
    static final int C_PLUM = 0xFFB65A9A;
    static final int C_AMBER = 0xFFD28A3A;
    static final int C_LIME = 0xFFD7ED86;

    static final String PREFS = "chiroot_prefs";
    static final String K_REFRESH = "refresh_token";
    static final String K_ACCESS = "access_token";
    static final String K_EXPIRES = "token_expires_at";
    static final String K_USER = "user_json";
    static final String K_PROFILE = "profile_json";
    static final String K_TRACKS = "guest_tracks_json";
    static final String K_HISTORY = "history_json";
    static final String K_SETTINGS = "settings_json";
    static final String K_VERIFIER = "pkce_verifier";

    static final int REQ_PICK_AUDIO = 4101;
    static final int REQ_PICK_COVER = 4102;
    static final int REQ_SAVE_SVG = 4103;
    static final int REQ_SAVE_BACKUP = 4104;
    static final int REQ_OPEN_BACKUP = 4105;
    static final int REQ_NOTIF = 4106;

    static final String DEFAULT_LABEL = "#D84A3A";
    static final String DEFAULT_VINYL = "#1E1E24";

    /* =====================================================================
     * Shared state
     * ===================================================================== */

    SharedPreferences prefs;
    Typeface bodyFont, boldFont, displayFont;
    final ExecutorService io = Executors.newFixedThreadPool(4);
    final Handler ui = new Handler(Looper.getMainLooper());

    // Session
    String accessToken = "";
    String refreshToken = "";
    long tokenExpiresAt = 0L;
    String userId = "";
    String userEmail = "";
    String userName = "";
    String userAvatar = "";
    String profileBio = "";
    String profileBanner = DEFAULT_LABEL;
    boolean guestMode = true;

    // Library + playback model
    final List<Track> crate = new ArrayList<>();
    final List<Hist> history = new ArrayList<>();
    String currentTrackId = "";
    int playingIndex = -1;
    boolean shuffleOn = false;
    int repeatMode = 0; // 0 off, 1 all, 2 one

    // Settings
    boolean dspEnabled = false;
    boolean showVinylSpin = true;

    // Playback
    MediaController controller;
    boolean controllerConnecting = false;
    long playStartedAt = 0L;

    // Root UI
    FrameLayout root;
    LinearLayout appBar;
    TextView greetingView;
    TextView avatarBadge;
    FrameLayout contentHost;
    ScrollView mainScroll;
    LinearLayout miniBar;
    TextView miniTitle, miniArtist;
    TextView miniPlayIcon;
    ImageView miniCover;
    ProgressBar miniProgress;

    // Now-playing widgets
    VinylView vinyl;
    TextView npTitle, npArtist, npEyebrow;
    SeekBar npSeek;
    TextView npCur, npDur;
    TextView btnPlayIcon, btnShuffle, btnLoop, btnMute;
    SeekBar volSeek;
    LinearLayout crateList, top5List;
    TextView crateCount, crateEmpty;
    EditText crateSearch;

    // Dialogs
    Dialog errorDialog;
    Dialog activeDialog;

    // Pending picker results
    Uri pendingAudioUri;
    Uri pendingCoverUri;
    ProgressBar uploadBar;
    TextView uploadLabel;
    boolean uploading = false;
    String pendingUploadId = "";
    String pendingSvgForSave = "";
    String pendingBackupForSave = "";

    // Retry bookkeeping for the single reusable error modal
    Runnable errorRetry;

    /* =====================================================================
     * 2. INITIALIZATION
     * ===================================================================== */

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bodyFont = ResourcesCompat.getFont(this, R.font.chiroot_body);
        boldFont = ResourcesCompat.getFont(this, R.font.chiroot_bold);
        displayFont = ResourcesCompat.getFont(this, R.font.chiroot_display);

        loadSession();
        loadSettings();
        loadLocalHistory();
        rebuildRoot();
        showAuthGate();

        if (!guestMode) {
            restoreFromCloud();
        }
        registerAudioDeviceWatcher();
        maybeAskNotificationPermission();
        connectController();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (controller == null && !controllerConnecting) connectController();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playStartedAt > 0) flushListenSession();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (deviceCallback != null) {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.unregisterAudioDeviceCallback(deviceCallback);
            deviceCallback = null;
        }
        if (ticker != null) ui.removeCallbacks(ticker);
        ticker = null;
        if (controller != null) controller.release();
        controller = null;
        io.shutdownNow();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOAuthDeepLink(intent);
    }

    @Override
    public void onBackPressed() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
            return;
        }
        if (mainScroll != null && mainScroll.getParent() != null) {
            super.onBackPressed();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            togglePlayPause();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /* =====================================================================
     * 3. UI HELPERS (all views are built programmatically)
     * ===================================================================== */

    /** Keeps every modal usable in landscape and on small phones. */
    static class ResponsiveScrollView extends ScrollView {
        ResponsiveScrollView(Context context) { super(context); }
        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxHeight = Math.max(dp(180), (int) (getResources().getDisplayMetrics().heightPixels * 0.76f));
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? boldFont : bodyFont);
        return t;
    }

    TextView label(String s) {
        return text(s, 12f, C_MUTED, true);
    }

    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    GradientDrawable bg(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    /** Card with the soft "paper" look of the original Chiroot sketches. */
    LinearLayout card(int fill) {
        LinearLayout c = column();
        c.setBackground(bg(fill, 22f, C_INK, 1.5f));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    TextView chip(String s, int fill, int textColor, View.OnClickListener click) {
        TextView t = text(s, 14f, textColor, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        t.setBackground(bg(fill, 18f, 0x22000000, 0f));
        t.setClickable(true);
        t.setFocusable(true);
        if (click != null) t.setOnClickListener(click);
        return t;
    }

    TextView primaryButton(String s, View.OnClickListener click) {
        return chip(s, C_RED, Color.WHITE, click);
    }

    TextView subtleButton(String s, View.OnClickListener click) {
        return chip(s, C_PAPER_2, C_INK, click);
    }

    TextView outlineButton(String s, View.OnClickListener click) {
        TextView t = text(s, 14f, C_INK, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setBackground(bg(Color.TRANSPARENT, 18f, C_INK, 1.5f));
        t.setClickable(true);
        t.setOnClickListener(click);
        return t;
    }

    LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    View spacerH(int dp) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(dp(dp), 1));
        return s;
    }

    View spacerV(int dp) {
        Space s = new Space(this);
        s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return s;
    }

    void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /* =====================================================================
     * 4. ROOT LAYOUT + NAVIGATION
     * ===================================================================== */

    void rebuildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(C_PAPER);
        root.setFitsSystemWindows(true);

        LinearLayout shell = column();
        shell.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- top bar ----
        appBar = row();
        appBar.setPadding(dp(16), dp(10), dp(12), dp(10));
        appBar.setBackgroundColor(C_PAPER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.chiroot_mark);
        logo.setLayoutParams(lp(dp(34), dp(34)));
        appBar.addView(logo);

        LinearLayout titles = column();
        titles.setPadding(dp(10), 0, 0, 0);
        TextView brand = text("Chiroot", 26f, C_INK, true);
        brand.setTypeface(displayFont);
        greetingView = text("", 12f, C_MUTED, false);
        titles.addView(brand);
        titles.addView(greetingView);
        appBar.addView(titles, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        avatarBadge = text("G", 15f, Color.WHITE, true);
        avatarBadge.setGravity(Gravity.CENTER);
        avatarBadge.setBackground(bg(C_WOOD, 24f, 0, 0));
        avatarBadge.setLayoutParams(lp(dp(38), dp(38)));
        avatarBadge.setOnClickListener(v -> showProfileDialog());
        appBar.addView(avatarBadge);

        TextView gear = text("⚙", 22f, C_INK, false);
        gear.setPadding(dp(12), 0, dp(6), 0);
        gear.setOnClickListener(v -> showWelcomeSheet());
        appBar.addView(gear);

        shell.addView(appBar, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- content host ----
        contentHost = new FrameLayout(this);
        shell.addView(contentHost, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- mini player bar ----
        miniBar = row();
        miniBar.setVisibility(View.GONE);
        miniBar.setBackground(bg(C_INK_SOFT, 0f, 0, 0));
        miniBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        miniCover = new ImageView(this);
        miniCover.setLayoutParams(lp(dp(42), dp(42)));
        miniCover.setBackground(bg(C_WOOD_DARK, 8f, 0, 0));
        miniBar.addView(miniCover);

        LinearLayout miniTexts = column();
        miniTexts.setPadding(dp(10), 0, dp(10), 0);
        miniTitle = text("", 14f, Color.WHITE, true);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniArtist = text("", 11f, 0xFFCFC8BC, false);
        miniArtist.setSingleLine(true);
        miniTexts.addView(miniTitle);
        miniTexts.addView(miniArtist);
        miniBar.addView(miniTexts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        miniPlayIcon = text("▶", 20f, Color.WHITE, false);
        miniPlayIcon.setPadding(dp(14), dp(6), dp(14), dp(6));
        miniPlayIcon.setOnClickListener(v -> togglePlayPause());
        miniBar.addView(miniPlayIcon);

        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setMax(1000);
        FrameLayout miniWrap = new FrameLayout(this);
        shell.addView(miniWrap, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        miniWrap.addView(miniBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams pip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM);
        miniWrap.addView(miniProgress, pip);
        miniProgress.setVisibility(View.GONE);

        setContentView(shell);
    }

    void setScreen(View v) {
        contentHost.removeAllViews();
        contentHost.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void refreshAppBar() {
        greetingView.setText(guestMode ? "mode tamu · koleksi lokal"
                : "selamat datang, " + (userName.isEmpty() ? "kawan" : userName));
        avatarBadge.setText(guestMode ? "G"
                : (userName.isEmpty() ? "C" : userName.substring(0, 1).toUpperCase(Locale.ROOT)));
        avatarBadge.setBackground(bg(guestMode ? C_WOOD : C_TEAL, 24f, 0, 0));
    }

    /* =====================================================================
     * 5. DATA MODEL
     * ===================================================================== */

    static class Track {
        String id = "";
        String title = "";
        String artist = "";
        String audioUrl = "";
        String coverUrl = "";
        String audioPath = "";
        String coverPath = "";
        String labelColor = DEFAULT_LABEL;
        String vinylColor = DEFAULT_VINYL;
        long durationMs = 0;
        boolean local = false;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", title);
            o.put("artist", artist);
            o.put("audioUrl", audioUrl);
            o.put("coverUrl", coverUrl);
            o.put("audioPath", audioPath);
            o.put("coverPath", coverPath);
            o.put("labelColor", labelColor);
            o.put("vinylColor", vinylColor);
            o.put("durationMs", durationMs);
            o.put("local", local);
            return o;
        }

        static Track fromJson(JSONObject o) {
            Track t = new Track();
            t.id = o.optString("id");
            t.title = o.optString("title");
            t.artist = o.optString("artist", "Unknown Artist");
            t.audioUrl = o.optString("audioUrl");
            t.coverUrl = o.optString("coverUrl");
            t.audioPath = o.optString("audioPath");
            t.coverPath = o.optString("coverPath");
            t.labelColor = safeColor(o.optString("labelColor"), DEFAULT_LABEL);
            t.vinylColor = safeColor(o.optString("vinylColor"), DEFAULT_VINYL);
            t.durationMs = o.optLong("durationMs");
            t.local = o.optBoolean("local");
            return t;
        }
    }

    static class Hist {
        String trackId = "";
        String title = "";
        String artist = "";
        long playedAt = 0;
        long durationMs = 0;

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("trackId", trackId);
            o.put("title", title);
            o.put("artist", artist);
            o.put("playedAt", playedAt);
            o.put("durationMs", durationMs);
            return o;
        }

        static Hist fromJson(JSONObject o) {
            Hist h = new Hist();
            h.trackId = o.optString("trackId");
            h.title = o.optString("title");
            h.artist = o.optString("artist");
            h.playedAt = o.optLong("playedAt");
            h.durationMs = o.optLong("durationMs");
            return h;
        }
    }

    /* =====================================================================
     * 6. SESSION, SETTINGS, LOCAL PERSISTENCE
     * ===================================================================== */

    void loadSession() {
        accessToken = prefs.getString(K_ACCESS, "");
        refreshToken = prefs.getString(K_REFRESH, "");
        tokenExpiresAt = prefs.getLong(K_EXPIRES, 0L);
        userId = prefs.getString("user_id", "");
        userEmail = prefs.getString("user_email", "");
        userName = prefs.getString("user_name", "");
        userAvatar = prefs.getString("user_avatar", "");
        profileBio = prefs.getString("user_bio", "");
        guestMode = accessToken.isEmpty() || userId.isEmpty();
        loadCrateCache();
    }

    void saveSession() {
        prefs.edit()
                .putString(K_ACCESS, accessToken)
                .putString(K_REFRESH, refreshToken)
                .putLong(K_EXPIRES, tokenExpiresAt)
                .putString("user_id", userId)
                .putString("user_email", userEmail)
                .putString("user_name", userName)
                .putString("user_avatar", userAvatar)
                .putString("user_bio", profileBio)
                .apply();
    }

    void clearSession() {
        accessToken = "";
        refreshToken = "";
        tokenExpiresAt = 0;
        userId = "";
        userEmail = "";
        userName = "";
        userAvatar = "";
        profileBio = "";
        guestMode = true;
        prefs.edit().remove(K_ACCESS).remove(K_REFRESH).remove(K_EXPIRES)
                .remove("user_id").remove("user_email").remove("user_name")
                .remove("user_avatar").remove("user_bio").apply();
    }

    void loadSettings() {
        try {
            JSONObject o = new JSONObject(prefs.getString(K_SETTINGS, "{}"));
            dspEnabled = o.optBoolean("dsp", false);
            showVinylSpin = o.optBoolean("spin", true);
        } catch (JSONException ignored) {
        }
    }

    void saveSettings() {
        try {
            JSONObject o = new JSONObject();
            o.put("dsp", dspEnabled);
            o.put("spin", showVinylSpin);
            prefs.edit().putString(K_SETTINGS, o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** Crate cache: guests keep their library locally, signed-in users get an offline cache. */
    void loadCrateCache() {
        crate.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_TRACKS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                Track t = Track.fromJson(arr.getJSONObject(i));
                if (!TextUtils.isEmpty(t.title)) crate.add(t);
            }
        } catch (JSONException ignored) {
        }
    }

    void saveCrateCache() {
        JSONArray arr = new JSONArray();
        for (Track t : crate) {
            try {
                arr.put(t.toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(K_TRACKS, arr.toString()).apply();
    }

    void loadLocalHistory() {
        history.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(K_HISTORY, "[]"));
            for (int i = 0; i < arr.length(); i++) history.add(Hist.fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
    }

    void saveLocalHistory() {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < Math.min(history.size(), 500); i++) {
            try {
                arr.put(history.get(i).toJson());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(K_HISTORY, arr.toString()).apply();
    }

    Track findTrack(String id) {
        for (Track t : crate) if (t.id.equals(id)) return t;
        return null;
    }

    Track currentTrack() {
        return findTrack(currentTrackId);
    }

    /* =====================================================================
     * 7. SMALL UTILITIES
     * ===================================================================== */

    static String trimSlash(String s) {
        if (s == null) return "";
        String v = s.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    static String safeColor(String value, String fallback) {
        if (value != null && value.matches("^#[0-9a-fA-F]{6}$")) return value.toUpperCase(Locale.ROOT);
        return fallback;
    }

    static String nowIso() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    static String humanTime(long ms) {
        if (ms <= 0) return "0:00";
        long total = ms / 1000;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    static String humanHours(long ms) {
        long minutes = ms / 60000;
        return String.format(Locale.US, "%dh %02dm", minutes / 60, minutes % 60);
    }

    String monthKey() {
        return new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
    }

    static String randomId() {
        byte[] b = new byte[12];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format(Locale.US, "%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    static String base64Url(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    boolean online() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities cap = cm.getNetworkCapabilities(n);
        return cap != null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    void runIo(Runnable r) {
        io.execute(r);
    }

    void runUi(Runnable r) {
        ui.post(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }

    /* =====================================================================
     * 8. VINYL VIEW — canvas-drawn record, tied to the real playback state
     * ===================================================================== */

    class VinylView extends View {
        Bitmap cover;
        float rotation = 0f;
        ValueAnimator spin;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        String label = "CHIROOT";
        int labelColor = C_RED;
        int vinylColor = C_VINYL;

        VinylView(Context ctx) {
            super(ctx);
        }

        void setArt(@Nullable Bitmap bmp) {
            cover = bmp;
            invalidate();
        }

        void setColors(String vinyl, String lab) {
            vinylColor = Color.parseColor(safeColor(vinyl, DEFAULT_VINYL));
            labelColor = Color.parseColor(safeColor(lab, DEFAULT_LABEL));
            invalidate();
        }

        void setPlaying(boolean playing) {
            if (!playing || !showVinylSpin) {
                if (spin != null) {
                    spin.cancel();
                    spin = null;
                }
                invalidate();
                return;
            }
            if (spin != null && spin.isRunning()) return;
            spin = ValueAnimator.ofFloat(rotation % 360f, rotation % 360f + 360f);
            spin.setDuration(4000);
            spin.setRepeatCount(ValueAnimator.INFINITE);
            spin.addUpdateListener(a -> {
                rotation = (float) a.getAnimatedValue();
                invalidate();
            });
            spin.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (spin != null) spin.cancel();
            spin = null;
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            super.onDraw(c);
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f - 2;

            // shadow
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x33000000);
            c.drawCircle(cx, cy + dp(3), r, p);

            // record body
            p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                    lighten(vinylColor, 0.18f), vinylColor, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);

            // grooves
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, r * 0.006f));
            for (int i = 1; i <= 9; i++) {
                p.setColor(i % 2 == 0 ? 0x22FFFFFF : 0x14000000);
                c.drawCircle(cx, cy, r * (0.42f + i * 0.058f), p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x22000000);
            c.drawCircle(cx, cy + r * 0.02f, r * 0.995f, p);

            // label
            c.save();
            c.rotate(rotation, cx, cy);
            float lr = r * 0.34f;
            p.setColor(Color.parseColor("#F4EFDF"));
            c.drawCircle(cx, cy, lr, p);
            if (cover != null) {
                Path clip = new Path();
                clip.addCircle(cx, cy, lr * 0.98f, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                RectF dst = new RectF(cx - lr, cy - lr, cx + lr, cy + lr);
                c.drawBitmap(cover, null, dst, p);
                c.restore();
            } else {
                p.setColor(labelColor);
                c.drawCircle(cx, cy, lr, p);
                p.setColor(Color.WHITE);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                p.setTextSize(lr * 0.30f);
                c.drawText("CHIROOT", cx, cy + lr * 0.08f, p);
                p.setTextSize(lr * 0.20f);
                p.setColor(0xCCFFFFFF);
                c.drawText(label, cx, cy + lr * 0.40f, p);
            }
            // spindle
            p.setColor(Color.parseColor("#F4EFDF"));
            c.drawCircle(cx, cy, r * 0.028f, p);
            p.setColor(0x66000000);
            c.drawCircle(cx, cy, r * 0.014f, p);
            c.restore();
        }

        int lighten(int color, float amount) {
            int r = Math.min(255, (int) (Color.red(color) * (1 + amount)));
            int g = Math.min(255, (int) (Color.green(color) * (1 + amount)));
            int b = Math.min(255, (int) (Color.blue(color) * (1 + amount)));
            return Color.rgb(r, g, b);
        }
    }

    /* =====================================================================
     * 9. HTTP LAYER — plain HttpURLConnection, no hidden magic
     * ===================================================================== */

    static class ApiException extends IOException {
        final int status;
        final String kind;   // network | timeout | server | auth | permission | validation | cancelled
        final String detail;

        ApiException(int status, String kind, String detail) {
            super(kind + " (" + status + ")");
            this.status = status;
            this.kind = kind;
            this.detail = detail;
        }

        boolean isConnectionProblem() {
            return "network".equals(kind) || "timeout".equals(kind) || "server".equals(kind);
        }
    }

    static class Resp {
        int status;
        String body = "";
    }

    /** Synchronous HTTP call; always run it on the io executor. */
    Resp http(String method, String urlStr, @Nullable JSONObject json, Map<String, String> extraHeaders,
              byte[] rawBody, String rawContentType) throws ApiException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/json");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (rawBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", rawContentType == null ? "application/octet-stream" : rawContentType);
                conn.setFixedLengthStreamingMode(rawBody.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(rawBody);
                }
            } else if (json != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            Resp r = new Resp();
            r.status = code;
            r.body = readStream(in);
            android.util.Log.d(TAG, method + " " + safeUrlForLog(urlStr) + " -> " + code);
            return r;
        } catch (java.net.SocketTimeoutException te) {
            throw new ApiException(0, "timeout", "Permintaan melebihi waktu tunggu.");
        } catch (IOException ioe) {
            throw new ApiException(0, "network", "Tidak dapat menghubungi server.");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String readStream(@Nullable InputStream in) {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } catch (IOException e) {
            return "";
        }
    }

    static String safeUrlForLog(String url) {
        int i = url.indexOf('?');
        return i > 0 ? url.substring(0, i) + "?..." : url;
    }

    ApiException classify(int status, String body) {
        String kind;
        if (status >= 500) kind = "server";
        else if (status == 401) kind = "auth";
        else if (status == 403) kind = "permission";
        else kind = "validation";
        String msg = body;
        try {
            JSONObject o = new JSONObject(body);
            msg = o.optString("message", o.optString("error_description", o.optString("error", body)));
        } catch (JSONException ignored) {
        }
        return new ApiException(status, kind, msg == null ? "" : msg);
    }

    Map<String, String> supabaseHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("apikey", SUPABASE_ANON_KEY);
        h.put("Authorization", "Bearer " + (TextUtils.isEmpty(accessToken) ? SUPABASE_ANON_KEY : accessToken));
        h.put("Prefer", "return=representation");
        return h;
    }

    /** REST call against PostgREST with automatic single-shot token refresh on 401. */
    String rest(String method, String path, @Nullable JSONObject body) throws ApiException {
        ensureFreshToken();
        Resp r = http(method, SUPABASE_URL + "/rest/v1/" + path, body, supabaseHeaders(), null, null);
        if (r.status == 401 && !TextUtils.isEmpty(refreshToken)) {
            if (refreshSession()) {
                r = http(method, SUPABASE_URL + "/rest/v1/" + path, body, supabaseHeaders(), null, null);
            }
        }
        if (r.status >= 400) throw classify(r.status, r.body);
        return r.body;
    }

    /** POST that merges on the primary key — safe to retry after a failure. */
    void restUpsert(String table, JSONObject body) throws ApiException {
        ensureFreshToken();
        Map<String, String> h = supabaseHeaders();
        h.put("Prefer", "resolution=merge-duplicates,return=representation");
        Resp r = http("POST", SUPABASE_URL + "/rest/v1/" + table, body, h, null, null);
        if (r.status >= 400) throw classify(r.status, r.body);
    }

    JSONArray restArray(String method, String path, @Nullable JSONObject body) throws ApiException {
        String raw = rest(method, path, body);
        try {
            return TextUtils.isEmpty(raw) ? new JSONArray() : new JSONArray(raw);
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    JSONObject restObject(String method, String path, @Nullable JSONObject body) throws ApiException {
        String raw = rest(method, path, body);
        try {
            JSONArray a = TextUtils.isEmpty(raw) ? new JSONArray() : new JSONArray(raw);
            return a.length() > 0 ? a.getJSONObject(0) : new JSONObject();
        } catch (JSONException e) {
            try {
                return new JSONObject(raw);
            } catch (JSONException e2) {
                return new JSONObject();
            }
        }
    }

    /** Upload a byte array to Supabase Storage (bucket chiroot-crate). */
    void storageUpload(String path, byte[] data, String contentType) throws ApiException {
        ensureFreshToken();
        Map<String, String> h = supabaseHeaders();
        h.put("x-upsert", "true");
        h.put("Cache-Control", "3600");
        Resp r = http("POST", SUPABASE_URL + "/storage/v1/object/" + CRATE_BUCKET + "/" + path,
                null, h, data, contentType);
        if (r.status >= 400) throw classify(r.status, r.body);
    }

    void storageDelete(String path) throws ApiException {
        Resp r = http("DELETE", SUPABASE_URL + "/storage/v1/object/" + CRATE_BUCKET + "/" + path,
                null, supabaseHeaders(), null, null);
        if (r.status >= 400 && r.status != 404) throw classify(r.status, r.body);
    }

    String publicStorageUrl(String path) {
        return SUPABASE_URL + "/storage/v1/object/public/" + CRATE_BUCKET + "/" + path;
    }

    /* =====================================================================
     * 10. AUTHENTICATION — Google OAuth through Supabase with PKCE + deep link
     * ===================================================================== */

    void startGoogleOAuth() {
        if (!online()) {
            showConnectionError("Kamu sedang offline. Login Google butuh koneksi internet.", this::startGoogleOAuth);
            return;
        }
        String verifier = randomId() + randomId();
        prefs.edit().putString(K_VERIFIER, verifier).apply();
        String challenge;
        try {
            // PKCE hashes the verifier's UTF-8 bytes, not the hexadecimal text of the hash.
            challenge = base64Url(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            toast("Perangkat tidak mendukung login aman.");
            return;
        }
        String url = SUPABASE_URL + "/auth/v1/authorize"
                + "?provider=google"
                + "&redirect_to=" + enc(OAUTH_REDIRECT_URI)
                + "&code_challenge=" + challenge
                + "&code_challenge_method=S256"
                + "&scopes=" + enc("email profile");
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            toast("Selesaikan login di browser, lalu kembali ke Chiroot.");
        } catch (ActivityNotFoundException e) {
            toast("Tidak ada browser untuk login Google.");
        }
    }

    String lastOAuthCode = "";

    void handleOAuthDeepLink(@Nullable Intent intent) {
        if (intent == null || intent.getData() == null) return;
        Uri data = intent.getData();
        String scheme = data.getScheme();
        if (!"id.my.chiroot".equals(scheme) && !"chiroot".equals(scheme)) return;

        String error = data.getQueryParameter("error_description");
        if (error == null) error = data.getQueryParameter("error");
        if (error != null) {
            // Cancel or denial is a normal user action — inline message, not the connection modal.
            toast("Login dibatalkan.");
            showAuthGate();
            return;
        }
        String code = data.getQueryParameter("code");
        if (TextUtils.isEmpty(code)) return;
        // The same deep link is delivered again on every onResume — exchange it only once.
        if (code.equals(lastOAuthCode)) return;
        lastOAuthCode = code;
        String verifier = prefs.getString(K_VERIFIER, "");
        exchangeCodeForSession(code, verifier);
    }

    void exchangeCodeForSession(String code, String verifier) {
        runIo(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("auth_code", code);
                body.put("code_verifier", verifier);
                Resp r = http("POST", SUPABASE_URL + "/auth/v1/token?grant_type=pkce", body,
                        baseAuthHeaders(), null, null);
                if (r.status >= 400) throw classify(r.status, r.body);
                applyTokenResponse(new JSONObject(r.body));
                runUi(() -> {
                    closeDialogs();
                    refreshAppBar();
                    loadCloudData(null);
                    toast("Login Google berhasil!");
                });
            } catch (ApiException e) {
                runUi(() -> {
                    if (e.isConnectionProblem()) {
                        showConnectionError(describe(e), () -> exchangeCodeForSession(code, verifier));
                    } else {
                        toast("Login belum selesai. Kode login tidak valid atau sudah dipakai; silakan login lagi.");
                        showAuthGate();
                    }
                });
            } catch (JSONException e) {
                runUi(() -> toast("Respons login tidak valid."));
            }
        });
    }

    Map<String, String> baseAuthHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("apikey", SUPABASE_ANON_KEY);
        h.put("Authorization", "Bearer " + SUPABASE_ANON_KEY);
        return h;
    }

    void applyTokenResponse(JSONObject o) throws JSONException {
        accessToken = o.optString("access_token");
        refreshToken = o.optString("refresh_token", refreshToken);
        long expiresIn = o.optLong("expires_in", 3600);
        tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        JSONObject user = o.optJSONObject("user");
        if (user != null) {
            userId = user.optString("id", userId);
            userEmail = user.optString("email", userEmail);
            JSONObject meta = user.optJSONObject("user_metadata");
            if (meta != null) {
                userName = meta.optString("full_name", meta.optString("name", userName));
                userAvatar = meta.optString("avatar_url", userAvatar);
            }
        }
        guestMode = TextUtils.isEmpty(accessToken);
        saveSession();
    }

    void ensureFreshToken() {
        if (TextUtils.isEmpty(accessToken)) return;
        if (System.currentTimeMillis() > tokenExpiresAt - 60000L && !TextUtils.isEmpty(refreshToken)) {
            refreshSession();
        }
    }

    /** Blocking refresh — call from an io thread only. */
    boolean refreshSession() {
        try {
            JSONObject body = new JSONObject();
            body.put("refresh_token", refreshToken);
            Resp r = http("POST", SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token", body,
                    baseAuthHeaders(), null, null);
            if (r.status >= 400) {
                if (r.status == 400 || r.status == 401) {
                    runUi(() -> {
                        clearSession();
                        refreshAppBar();
                        showAuthGate();
                        toast("Sesi berakhir. Silakan login lagi.");
                    });
                }
                return false;
            }
            applyTokenResponse(new JSONObject(r.body));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void signOut() {
        final String token = accessToken;
        runIo(() -> {
            try {
                Map<String, String> h = new HashMap<>();
                h.put("apikey", SUPABASE_ANON_KEY);
                h.put("Authorization", "Bearer " + token);
                http("POST", SUPABASE_URL + "/auth/v1/logout", new JSONObject(), h, null, null);
            } catch (Exception ignored) {
            }
        });
        clearSession();
        crate.clear();
        saveCrateCache();
        refreshAppBar();
        updatePlayerUi();
        showAuthGate();
        toast("Kamu sudah logout.");
    }

    /** Pull profile + crate + history from Supabase. */
    void loadCloudData(@Nullable Runnable done) {
        if (guestMode) {
            if (done != null) done.run();
            return;
        }
        runIo(() -> {
            Throwable fail = null;
            try {
                JSONArray rows = restArray("GET", "profiles?select=*&id=eq." + enc(userId) + "&limit=1", null);
                if (rows.length() > 0) {
                    JSONObject p = rows.getJSONObject(0);
                    userName = p.optString("display_name", userName);
                    profileBio = p.optString("description", profileBio);
                    userAvatar = p.optString("pfp", userAvatar);
                    profileBanner = safeColor(p.optString("banner_color"), DEFAULT_LABEL);
                    saveSession();
                } else {
                    JSONObject p = new JSONObject();
                    p.put("id", userId);
                    p.put("display_name", userName.isEmpty() ? (userEmail.split("@")[0]) : userName);
                    p.put("email", userEmail);
                    p.put("description", profileBio);
                    p.put("pfp", userAvatar);
                    p.put("banner_color", profileBanner);
                    restUpsert("profiles", p);
                }
            } catch (Exception e) {
                fail = e;
            }
            try {
                loadCrateFromCloudBlocking();
            } catch (Exception e) {
                if (fail == null) fail = e;
            }
            try {
                loadHistoryFromCloudBlocking();
            } catch (Exception ignored) {
            }
            if (fail != null) android.util.Log.w(TAG, "loadCloudData: " + fail.getMessage());
            Throwable finalFail = fail;
            runUi(() -> {
                refreshAppBar();
                renderDashboard();
                if (done != null) done.run();
                if (finalFail != null) {
                    showOperationError(finalFail, "Data cloud belum dimuat", () -> loadCloudData(null));
                }
            });
        });
    }

    String describe(Throwable t) {
        if (t instanceof ApiException) {
            ApiException e = (ApiException) t;
            if ("network".equals(e.kind)) return "Tidak ada koneksi internet yang bisa dipakai.";
            if ("timeout".equals(e.kind)) return "Server terlalu lama merespons.";
            if ("server".equals(e.kind)) return "Server Chiroot sedang bermasalah.";
            return e.detail;
        }
        return "Terjadi kesalahan yang tidak terduga.";
    }

    /* =====================================================================
     * 11. THE CRATE — cloud library (table: crate_tracks, bucket: chiroot-crate)
     * ===================================================================== */

    void loadCrateFromCloudBlocking() throws ApiException {
        if (guestMode) return;
        JSONArray rows = restArray("GET",
                "crate_tracks?select=*&user_id=eq." + enc(userId) + "&order=position.asc", null);
        List<Track> loaded = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            Track t = new Track();
            t.id = o.optString("id");
            t.title = o.optString("title");
            if (TextUtils.isEmpty(t.title)) continue;
            t.artist = o.optString("artist", "Unknown Artist");
            t.labelColor = safeColor(o.optString("label_color"), DEFAULT_LABEL);
            t.vinylColor = safeColor(o.optString("vinyl_color"), DEFAULT_VINYL);
            t.audioPath = o.optString("audio_path", "");
            t.coverPath = o.optString("cover_path", "");
            t.audioUrl = o.optString("audio_url", "");
            if (TextUtils.isEmpty(t.audioUrl) && !TextUtils.isEmpty(t.audioPath)) {
                t.audioUrl = publicStorageUrl(t.audioPath);
            }
            t.coverUrl = TextUtils.isEmpty(t.coverPath) ? "" : publicStorageUrl(t.coverPath);
            t.durationMs = o.optLong("duration_ms", 0);
            if (TextUtils.isEmpty(t.audioUrl)) continue;
            loaded.add(t);
        }
        crate.clear();
        crate.addAll(loaded);
        saveCrateCache();
    }

    long parseTimestamp(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        try {
            return java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli();
        } catch (java.time.format.DateTimeParseException ignored) {
            try {
                return java.time.Instant.parse(value).toEpochMilli();
            } catch (Exception ignored2) {
                return 0;
            }
        }
    }

    void loadHistoryFromCloudBlocking() throws ApiException {
        if (guestMode) return;
        JSONArray rows = restArray("GET",
                "play_events?select=*&user_id=eq." + enc(userId) + "&order=created_at.desc&limit=300", null);
        List<Hist> loaded = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.optJSONObject(i);
            if (o == null) continue;
            Hist h = new Hist();
            h.trackId = o.optString("track_id");
            h.title = o.optString("title");
            h.artist = o.optString("artist");
            h.playedAt = parseTimestamp(o.optString("created_at", ""));
            h.durationMs = Math.max(0, o.optLong("duration_ms", 0));
            loaded.add(h);
        }
        history.clear();
        history.addAll(loaded);
        saveLocalHistory();
    }

    void pushPlayEvent(Track t) {
        if (guestMode || t == null || TextUtils.isEmpty(userId)) return;
        runIo(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("user_id", userId);
                body.put("track_id", t.id);
                body.put("title", t.title);
                body.put("artist", t.artist);
                rest("POST", "play_events", body);
            } catch (Exception ignored) {
                // A missed analytics row must never interrupt playback.
            }
        });
    }

    /* =====================================================================
     * 12. ADD MUSIC — SAF pickers, Supabase Storage upload with progress
     * ===================================================================== */

    void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_AUDIO);
        } catch (ActivityNotFoundException e) {
            toast("Tidak ada aplikasi file manager.");
        }
    }

    void pickCover() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_COVER);
        } catch (ActivityNotFoundException e) {
            toast("Tidak ada aplikasi galeri.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (requestCode == REQ_SAVE_SVG) pendingSvgForSave = "";
            if (requestCode == REQ_SAVE_BACKUP) pendingBackupForSave = "";
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_PICK_AUDIO && uri != null) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
            }
            pendingAudioUri = uri;
            showAddMusicDialog();
        } else if (requestCode == REQ_PICK_COVER && uri != null) {
            pendingCoverUri = uri;
            showAddMusicDialog();
        } else if (requestCode == REQ_SAVE_SVG && uri != null) {
            writeTextToUri(uri, pendingSvgForSave);
        } else if (requestCode == REQ_SAVE_BACKUP && uri != null) {
            writeBytesToUri(uri, pendingBackupBytes);
        } else if (requestCode == REQ_OPEN_BACKUP && uri != null) {
            restoreBackupFrom(uri);
        }
    }

    void writeTextToUri(Uri uri, String content) {
        final String payload = content;
        runIo(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) throw new IOException("stream null");
                os.write(payload.getBytes(StandardCharsets.UTF_8));
                os.flush();
                runUi(() -> toast("File tersimpan."));
            } catch (IOException e) {
                runUi(() -> toast("Gagal menyimpan file: " + e.getMessage()));
            }
        });
    }

    void writeBytesToUri(Uri uri, @Nullable byte[] content) {
        if (content == null || content.length == 0) {
            toast("Tidak ada data backup untuk disimpan.");
            return;
        }
        final byte[] payload = content;
        runIo(() -> {
            try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                if (os == null) throw new IOException("stream null");
                os.write(payload);
                os.flush();
                runUi(() -> toast("Backup ZIP tersimpan."));
            } catch (IOException e) {
                runUi(() -> toast("Gagal menyimpan backup: " + e.getMessage()));
            }
        });
    }

    String queryDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment() == null ? "track" : uri.getLastPathSegment();
    }

    byte[] readBytes(Uri uri, int maxMb) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Tidak bisa membaca file");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int limit = maxMb * 1024 * 1024;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > limit) throw new IOException("File melebihi " + maxMb + " MB");
            }
            return bo.toByteArray();
        }
    }

    void commitAddMusic(JSONObject fields, @Nullable Uri audioUri, @Nullable Uri coverUri) {
        if (uploading) {
            toast("Tunggu upload sebelumnya selesai.");
            return;
        }
        uploading = true;
        if (uploadBar != null) uploadBar.setVisibility(View.VISIBLE);
        if (uploadLabel != null) uploadLabel.setText("Menyiapkan upload…");

        final String title = fields.optString("title");
        final String artist = fields.optString("artist");
        final String label = safeColor(fields.optString("label"), DEFAULT_LABEL);
        final String vinyl = safeColor(fields.optString("vinyl"), DEFAULT_VINYL);
        final String localAudioUri = audioUri == null ? "" : audioUri.toString();
        final String localCoverUri = coverUri == null ? "" : coverUri.toString();
        if (TextUtils.isEmpty(pendingUploadId)) pendingUploadId = java.util.UUID.randomUUID().toString();
        final String id = pendingUploadId;

        runIo(() -> {
            byte[] audio = null;
            byte[] cover = null;
            try {
                if (audioUri != null) {
                    setUploadStatus("Membaca file audio…", 8);
                    audio = readBytes(audioUri, 60);
                }
                if (coverUri != null) {
                    setUploadStatus("Membaca cover…", 20);
                    cover = readBytes(coverUri, 4);
                }

                String audioPath = null;
                String coverPath = null;

                if (guestMode) {
                    // Guest mode: keep the picked content URIs, no cloud round-trip.
                    Track t = new Track();
                    t.id = id;
                    t.title = title;
                    t.artist = artist;
                    t.audioUrl = localAudioUri;
                    t.coverUrl = localCoverUri;
                    t.labelColor = label;
                    t.vinylColor = vinyl;
                    t.local = true;
                    pendingUploadId = "";
                    runUi(() -> {
                        crate.add(0, t);
                        saveCrateCache();
                        syncQueue();
                        uploading = false;
                        finishUploadUi("Ditambahkan ke crate lokal");
                        renderDashboard();
                        closeDialogs();
                    });
                    return;
                }

                if (audioUri != null) {
                    audioPath = userId + "/" + id + ".mp3";
                    setUploadStatus("Upload audio ke Supabase…", 45);
                    storageUpload(audioPath, audio, audioUri != null ? mimeOf(audioUri, "audio/mpeg") : "audio/mpeg");
                }
                if (coverUri != null) {
                    coverPath = userId + "/" + id + "_cover.jpg";
                    setUploadStatus("Upload cover…", 75);
                    storageUpload(coverPath, compressImage(cover), "image/jpeg");
                }

                setUploadStatus("Menyimpan metadata…", 92);
                JSONObject row = new JSONObject();
                row.put("id", id);
                row.put("user_id", userId);
                row.put("title", title);
                row.put("artist", artist);
                row.put("label_color", label);
                row.put("vinyl_color", vinyl);
                row.put("audio_path", audioPath);
                row.put("audio_url", "");
                row.put("cover_path", coverPath);
                row.put("position", crate.size());
                // Upsert on the primary key so "Try Again" can never create a duplicate row.
                restUpsert("crate_tracks", row);

                Track t = new Track();
                t.id = id;
                t.title = title;
                t.artist = artist;
                t.audioPath = audioPath == null ? "" : audioPath;
                t.coverPath = coverPath == null ? "" : coverPath;
                t.audioUrl = audioPath == null ? "" : publicStorageUrl(audioPath);
                t.coverUrl = coverPath == null ? "" : publicStorageUrl(coverPath);
                t.labelColor = label;
                t.vinylColor = vinyl;

                pendingUploadId = "";
                runUi(() -> {
                    crate.add(0, t);
                    saveCrateCache();
                    syncQueue();
                    uploading = false;
                    finishUploadUi("Lagu tersimpan di The Crate");
                    renderDashboard();
                    closeDialogs();
                });
            } catch (Exception e) {
                // Never claim success for a failed upload.
                runUi(() -> {
                    uploading = false;
                    if (uploadBar != null) uploadBar.setVisibility(View.GONE);
                    if (uploadLabel != null) uploadLabel.setText("Upload gagal — belum masuk ke The Crate.");
                    showOperationError(e, "Upload gagal", () -> commitAddMusic(fields, audioUri, coverUri));
                });
            }
        });
    }

    void setUploadStatus(String label, int pct) {
        runUi(() -> {
            if (uploadLabel != null) uploadLabel.setText(label);
            if (uploadBar != null) {
                uploadBar.setVisibility(View.VISIBLE);
                uploadBar.setProgress(pct);
            }
        });
    }

    void finishUploadUi(String msg) {
        if (uploadBar != null) uploadBar.setVisibility(View.GONE);
        if (uploadLabel != null) uploadLabel.setText(msg);
        toast(msg);
    }

    String mimeOf(Uri uri, String fallback) {
        String m = getContentResolver().getType(uri);
        return TextUtils.isEmpty(m) ? fallback : m;
    }

    byte[] compressImage(byte[] raw) throws IOException {
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        if (bmp == null) throw new IOException("Cover bukan gambar yang valid");
        int max = 900;
        if (bmp.getWidth() > max || bmp.getHeight() > max) {
            float s = max / (float) Math.max(bmp.getWidth(), bmp.getHeight());
            bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * s),
                    Math.round(bmp.getHeight() * s), true);
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bo);
        return bo.toByteArray();
    }

    void deleteTrack(Track t) {
        if (guestMode || t.local) {
            crate.remove(t);
            saveCrateCache();
            syncQueue();
            renderDashboard();
            toast("Lagu dihapus dari crate lokal.");
            return;
        }
        runIo(() -> {
            try {
                rest("DELETE", "crate_tracks?id=eq." + enc(t.id), null);
                if (!TextUtils.isEmpty(t.audioPath)) {
                    try {
                        storageDelete(t.audioPath);
                    } catch (Exception ignored) {
                    }
                }
                if (!TextUtils.isEmpty(t.coverPath)) {
                    try {
                        storageDelete(t.coverPath);
                    } catch (Exception ignored) {
                    }
                }
                runUi(() -> {
                    crate.remove(t);
                    saveCrateCache();
                    syncQueue();
                    renderDashboard();
                    toast("Lagu dihapus.");
                });
            } catch (Exception e) {
                runUi(() -> showOperationError(e, "Lagu belum dihapus", () -> deleteTrack(t)));
            }
        });
    }

    /* =====================================================================
     * 13. PLAYBACK — Media3 / ExoPlayer through a MediaSessionService
     *
     * The service (documented single-file exception) owns the player so playback
     * survives the Activity, lock screen and app switching. The Activity talks to it
     * through a MediaController, so there is exactly one player instance.
     * ===================================================================== */

    @UnstableApi
    public static class ChirootPlaybackService extends MediaSessionService {
        MediaSession session;
        ExoPlayer exo;
        SharedPreferences.OnSharedPreferenceChangeListener dspListener;
        android.media.audiofx.Equalizer equalizer;
        int eqSessionId = -1;
        boolean eqWanted = false;

        @Override
        public void onCreate() {
            super.onCreate();
            androidx.media3.common.AudioAttributes attrs = new androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build();
            // ExoPlayer handles audio focus for us: duck on transient loss, pause on
            // permanent loss, resume only after an eligible transient interruption.
            exo = new ExoPlayer.Builder(this)
                    .setAudioAttributes(attrs, true)
                    .setHandleAudioBecomingNoisy(true)
                    .setSeekBackIncrementMs(10000)
                    .setSeekForwardIncrementMs(10000)
                    .build();
            eqWanted = readDspSetting(getSharedPreferences(PREFS, MODE_PRIVATE));
            DefaultMediaNotificationProvider notifications = new DefaultMediaNotificationProvider.Builder(this)
                    .setChannelId("chiroot_playback")
                    .setChannelName(R.string.playback_channel_name)
                    .build();
            notifications.setSmallIcon(R.drawable.ic_notification);
            setMediaNotificationProvider(notifications);
            dspListener = (sp, key) -> {
                if (K_SETTINGS.equals(key)) {
                    eqWanted = readDspSetting(sp);
                    applyEqualizer();
                }
            };
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(dspListener);

            session = new MediaSession.Builder(this, exo)
                    .setBitmapLoader(new androidx.media3.common.util.CacheBitmapLoader(
                            new androidx.media3.common.util.SimpleBitmapLoader()))
                    .setSessionActivity(pendingIntentForApp())
                    .build();

            exo.addListener(new Player.Listener() {
                @Override
                public void onAudioSessionIdChanged(int audioSessionId) {
                    applyEqualizer();
                }

                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) applyEqualizer();
                }
            });
            applyEqualizer();
        }

        /**
         * Playback DSP: a platform Equalizer bound to the ExoPlayer audio session.
         *
         * This is real audio processing on the decoded playback stream (not microphone
         * capture). True *environmental* noise cancellation needs headphone hardware; the
         * app never claims otherwise.
         */
        boolean readDspSetting(SharedPreferences sp) {
            try {
                return new JSONObject(sp.getString(K_SETTINGS, "{}")).optBoolean("dsp", false);
            } catch (JSONException e) {
                return false;
            }
        }

        void applyEqualizer() {
            int sessionId = exo == null ? -1 : exo.getAudioSessionId();
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (sessionId <= 0) {
                sp.edit().putString("dsp_status", "Menunggu sesi audio (putar lagu dulu)").apply();
                return;
            }
            try {
                if (equalizer == null || sessionId != eqSessionId) {
                    releaseEqualizer();
                    equalizer = new android.media.audiofx.Equalizer(0, sessionId);
                    eqSessionId = sessionId;
                    setupBands();
                }
                equalizer.setEnabled(eqWanted);
                boolean actuallyEnabled = equalizer.getEnabled();
                sp.edit().putString("dsp_status", actuallyEnabled
                        ? "Aktif — EQ gramofon hangat pada sesi audio " + sessionId
                        : (eqWanted ? "Tidak dapat diaktifkan pada perangkat ini" : "Nonaktif — audio asli")).apply();
            } catch (Throwable t) {
                releaseEqualizer();
                sp.edit().putString("dsp_status", "Tidak didukung perangkat ini").apply();
            }
        }

        void setupBands() throws Exception {
            if (equalizer == null) return;
            short bands = equalizer.getNumberOfBands();
            short[] range = equalizer.getBandLevelRange();
            int min = range[0];
            int max = range[1];
            // Warm gramophone curve, scaled to the device's own band level range.
            float[] curve = {0.55f, 0.30f, 0.05f, -0.10f, 0.25f};
            for (short b = 0; b < bands; b++) {
                float target = curve[Math.min(curve.length - 1, b * curve.length / Math.max(1, bands))];
                short level = (short) (target > 0 ? target * max : -target * min);
                level = (short) Math.max(min, Math.min(max, level));
                equalizer.setBandLevel(b, level);
            }
        }

        void releaseEqualizer() {
            if (equalizer != null) {
                try {
                    equalizer.release();
                } catch (Throwable ignored) {
                }
                equalizer = null;
                eqSessionId = -1;
            }
        }

        android.app.PendingIntent pendingIntentForApp() {
            Intent i = new Intent(this, MainActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            return android.app.PendingIntent.getActivity(this, 0, i,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        }

        @Nullable
        @Override
        public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
            return session;
        }

        @Override
        public void onTaskRemoved(@Nullable Intent rootIntent) {
            Player p = session == null ? null : session.getPlayer();
            if (p == null || !p.getPlayWhenReady() || p.getMediaItemCount() == 0) {
                stopSelf();
            }
            super.onTaskRemoved(rootIntent);
        }

        @Override
        public void onDestroy() {
            if (dspListener != null) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .unregisterOnSharedPreferenceChangeListener(dspListener);
                dspListener = null;
            }
            releaseEqualizer();
            if (session != null) {
                session.release();
                session = null;
            }
            if (exo != null) {
                exo.release();
                exo = null;
            }
            super.onDestroy();
        }
    }

    /** Attach the Activity to the single player instance living in the service. */
    void connectController() {
        if (controller != null || controllerConnecting) return;
        controllerConnecting = true;
        SessionToken token = new SessionToken(this,
                new ComponentName(this, ChirootPlaybackService.class));
        ListenableFuture<MediaController> fut = new MediaController.Builder(this, token).buildAsync();
        fut.addListener(() -> {
            controllerConnecting = false;
            try {
                controller = fut.get();
                attachPlayerListeners();
                restoreQueueIntoController();
                updatePlayerUi();
            } catch (Exception e) {
                android.util.Log.w(TAG, "controller failed: " + e.getMessage());
                controller = null;
            }
        }, MoreExecutors.directExecutor());
    }

    void attachPlayerListeners() {
        if (controller == null) return;
        controller.addListener(new Player.Listener() {
            @Override
            public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
                if (mediaItem != null && mediaItem.mediaId != null) {
                    currentTrackId = mediaItem.mediaId;
                }
                ui.post(() -> {
                    syncNowPlayingFromPlayer();
                    updateMiniBar();
                });
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                ui.post(() -> {
                    if (vinyl != null) vinyl.setPlaying(isPlaying);
                    updatePlayIcons(isPlaying);
                    if (isPlaying) {
                        if (playStartedAt == 0) playStartedAt = SystemClock.elapsedRealtime();
                        Track t = currentTrack();
                        if (t != null) {
                            recordListen(t);
                            pushPlayEvent(t);
                        }
                    } else {
                        flushListenSession();
                    }
                });
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                ui.post(() -> {
                    updateMiniBar();
                    if (state == Player.STATE_ENDED) flushListenSession();
                    // Media3 itself owns next/repeat/shuffle transitions. Advancing here
                    // again would skip a track or break repeat-one.
                });
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                ui.post(() -> {
                    String msg = "Playback gagal. Periksa koneksi atau file lagu.";
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                            || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
                        showConnectionError(msg, () -> {
                            if (controller != null) controller.prepare();
                        });
                    } else {
                        toast(msg);
                    }
                });
            }

            @Override
            public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
                ui.post(() -> {
                    shuffleOn = shuffleModeEnabled;
                    updatePlayIcons(controller != null && controller.isPlaying());
                });
            }

            @Override
            public void onRepeatModeChanged(int newRepeatMode) {
                ui.post(() -> {
                    repeatMode = newRepeatMode;
                    updatePlayIcons(controller != null && controller.isPlaying());
                });
            }
        });
        if (controller != null) {
            shuffleOn = controller.getShuffleModeEnabled();
            repeatMode = controller.getRepeatMode();
        }
    }

    void restoreQueueIntoController() {
        if (controller == null) return;
        if (controller.getMediaItemCount() == 0 && !crate.isEmpty()) {
            List<MediaItem> items = new ArrayList<>();
            for (Track t : crate) items.add(mediaItemFor(t));
            controller.setMediaItems(items, Math.max(0, indexOfTrack(currentTrackId)), 0L);
            controller.setPlayWhenReady(false);
        }
        controller.setVolume(prefs.getFloat("volume", 1f));
    }

    MediaItem mediaItemFor(Track t) {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle("Chiroot Crate");
        if (!TextUtils.isEmpty(t.coverUrl)) md.setArtworkUri(Uri.parse(t.coverUrl));
        return new MediaItem.Builder()
                .setMediaId(t.id)
                .setUri(t.audioUrl)
                .setMediaMetadata(md.build())
                .build();
    }

    int indexOfTrack(String id) {
        for (int i = 0; i < crate.size(); i++) if (crate.get(i).id.equals(id)) return i;
        return -1;
    }

    void syncQueue() {
        if (controller == null) return;
        if (crate.isEmpty()) {
            controller.stop();
            controller.clearMediaItems();
            currentTrackId = "";
            updatePlayerUi();
            updateMiniBar();
            return;
        }
        List<MediaItem> items = new ArrayList<>();
        for (Track t : crate) items.add(mediaItemFor(t));
        int idx = Math.max(0, Math.min(indexOfTrack(currentTrackId), Math.max(0, items.size() - 1)));
        boolean wasPlaying = controller.isPlaying();
        controller.setMediaItems(items, idx, controller.getCurrentPosition());
        controller.setPlayWhenReady(wasPlaying);
    }

    void playTrack(Track t) {
        if (controller == null) {
            connectController();
            currentTrackId = t.id;
            toast("Menyiapkan pemutar…");
            ui.postDelayed(() -> playTrack(t), 900);
            return;
        }
        int idx = indexOfTrack(t.id);
        if (idx < 0) {
            toast("Lagu tidak ada di crate.");
            return;
        }
        currentTrackId = t.id;
        playingIndex = idx;
        if (controller.getMediaItemCount() == 0) {
            restoreQueueIntoController();
        }
        if (idx >= 0 && idx < controller.getMediaItemCount()
                && !t.id.equals(currentTrackIdOfController())) {
            controller.seekTo(idx, 0);
        }
        controller.setPlayWhenReady(true);
        controller.prepare();
        updatePlayerUi();
        updateMiniBar();
    }

    String currentTrackIdOfController() {
        if (controller == null || controller.getCurrentMediaItem() == null) return "";
        return controller.getCurrentMediaItem().mediaId;
    }

    void togglePlayPause() {
        if (controller == null) {
            Track t = currentTrack() != null ? currentTrack() : (crate.isEmpty() ? null : crate.get(0));
            if (t != null) playTrack(t);
            return;
        }
        if (controller.isPlaying()) controller.pause();
        else {
            if (controller.getMediaItemCount() == 0 && !crate.isEmpty()) {
                restoreQueueIntoController();
                controller.seekTo(indexOfTrack(currentTrackId), 0);
            }
            controller.play();
        }
    }

    void nextTrack() {
        if (controller == null) return;
        controller.seekToNextMediaItem();
        controller.play();
    }

    void prevTrack() {
        if (controller == null) return;
        if (controller.getCurrentPosition() > 4000) {
            controller.seekTo(0);
        } else {
            controller.seekToPreviousMediaItem();
            controller.play();
        }
    }

    void onTrackEnded() {
        if (controller == null) return;
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            controller.seekTo(0);
            controller.play();
        } else if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem();
            controller.play();
        } else if (repeatMode == Player.REPEAT_MODE_ALL && controller.getMediaItemCount() > 0) {
            controller.seekTo(0, 0);
            controller.play();
        } else {
            flushListenSession();
        }
    }

    /* ---- listening history (feeds the report) ---- */

    void recordListen(Track t) {
        // Collapse repeated isPlaying=true events for the same track into one history entry.
        if (!history.isEmpty() && history.get(0).trackId.equals(t.id)
                && System.currentTimeMillis() - history.get(0).playedAt < 30000L) {
            return;
        }
        Hist h = new Hist();
        h.trackId = t.id;
        h.title = t.title;
        h.artist = t.artist;
        h.playedAt = System.currentTimeMillis();
        h.durationMs = 0;
        history.add(0, h);
        while (history.size() > 500) history.remove(history.size() - 1);
        saveLocalHistory();
    }

    void flushListenSession() {
        if (playStartedAt <= 0) return;
        long played = SystemClock.elapsedRealtime() - playStartedAt;
        playStartedAt = 0;
        if (played < 5000) return;
        for (Hist h : history) {
            if (h.durationMs == 0 && h.trackId.equals(currentTrackId)) {
                h.durationMs = played;
                break;
            }
        }
        saveLocalHistory();
    }

    /* =====================================================================
     * 14. AUDIO OUTPUT, HEADPHONES, VOLUME
     * ===================================================================== */

    AudioDeviceCallback deviceCallback;

    void registerAudioDeviceWatcher() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        deviceCallback = new AudioDeviceCallback() {
            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                for (AudioDeviceInfo d : removed) {
                    int type = d.getType();
                    boolean wasHeadphone = type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                            || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || type == AudioDeviceInfo.TYPE_USB_HEADSET;
                    if (wasHeadphone && controller != null && controller.isPlaying()) {
                        controller.pause();
                        toast("Perangkat audio terputus — musik dijeda.");
                    }
                }
            }
        };
        am.registerAudioDeviceCallback(deviceCallback, ui);
    }

    void setVolumePercent(int percent) {
        float v = Math.max(0f, Math.min(1f, percent / 100f));
        prefs.edit().putFloat("volume", v).apply();
        if (controller != null) controller.setVolume(v);
    }

    void toggleMute() {
        if (controller == null) return;
        boolean muted = controller.getVolume() == 0f;
        controller.setVolume(muted ? prefs.getFloat("volume", 1f) : 0f);
        updatePlayerUi();
    }

    /* =====================================================================
     * 15. DASHBOARD + NOW PLAYING UI
     * ===================================================================== */

    final Map<String, Bitmap> bmpCache = new HashMap<>();
    Runnable ticker;

    void showDashboard() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(4), dp(16), dp(28));
        sv.addView(body);
        mainScroll = sv;

        body.addView(nowPlayingCard());
        body.addView(quickActions());
        body.addView(crateCard());
        body.addView(top5Card());

        setScreen(sv);
        refreshAppBar();
        renderDashboard();
        startTicker();
    }

    View nowPlayingCard() {
        LinearLayout c = card(C_PAPER_2);

        npEyebrow = label("SEKARANG DIPUTAR");
        npEyebrow.setLetterSpacing(0.12f);
        c.addView(npEyebrow);

        vinyl = new VinylView(this);
        FrameLayout vWrap = new FrameLayout(this);
        vWrap.setPadding(0, dp(10), 0, dp(6));
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER);
        vWrap.addView(vinyl, vlp);
        c.addView(vWrap, matchWrap());

        npTitle = text("No record loaded", 28f, C_INK, true);
        npTitle.setTypeface(displayFont);
        npTitle.setGravity(Gravity.CENTER);
        npTitle.setSingleLine(true);
        npTitle.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(npTitle);

        npArtist = text("Tambahkan lagu ke crate untuk mulai", 14f, C_MUTED, false);
        npArtist.setGravity(Gravity.CENTER);
        npArtist.setSingleLine(true);
        npArtist.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(npArtist);

        LinearLayout seekRow = row();
        seekRow.setPadding(0, dp(12), 0, 0);
        npCur = text("0:00", 11f, C_MUTED, false);
        npDur = text("0:00", 11f, C_MUTED, false);
        npSeek = new SeekBar(this);
        npSeek.setMax(1000);
        npSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && controller != null) {
                    long dur = controller.getDuration();
                    if (dur > 0) {
                        controller.seekTo(dur * progress / 1000L);
                        npCur.setText(humanTime(dur * progress / 1000L));
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        seekRow.addView(npCur, lp(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));
        seekRow.addView(npSeek, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        seekRow.addView(npDur, lp(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT));
        c.addView(seekRow);

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(12), 0, dp(4));

        btnShuffle = text("⤨", 20f, C_MUTED, false);
        btnShuffle.setGravity(Gravity.CENTER);
        btnShuffle.setLayoutParams(lp(dp(48), dp(48)));
        btnShuffle.setOnClickListener(v -> {
            shuffleOn = !shuffleOn;
            if (controller != null) controller.setShuffleModeEnabled(shuffleOn);
            updatePlayIcons(controller != null && controller.isPlaying());
        });
        controls.addView(btnShuffle);

        TextView prev = text("⏮", 26f, C_INK, false);
        prev.setGravity(Gravity.CENTER);
        prev.setLayoutParams(lp(dp(56), dp(56)));
        prev.setOnClickListener(v -> prevTrack());
        controls.addView(prev);

        btnPlayIcon = text("▶", 30f, Color.WHITE, false);
        btnPlayIcon.setGravity(Gravity.CENTER);
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(C_RED);
        btnPlayIcon.setBackground(playBg);
        btnPlayIcon.setLayoutParams(lp(dp(72), dp(72)));
        btnPlayIcon.setOnClickListener(v -> togglePlayPause());
        controls.addView(btnPlayIcon);

        TextView next = text("⏭", 26f, C_INK, false);
        next.setGravity(Gravity.CENTER);
        next.setLayoutParams(lp(dp(56), dp(56)));
        next.setOnClickListener(v -> nextTrack());
        controls.addView(next);

        btnLoop = text("↻", 20f, C_MUTED, false);
        btnLoop.setGravity(Gravity.CENTER);
        btnLoop.setLayoutParams(lp(dp(48), dp(48)));
        btnLoop.setOnClickListener(v -> {
            repeatMode = (repeatMode + 1) % 3;
            if (controller != null) {
                controller.setRepeatMode(repeatMode == 0 ? Player.REPEAT_MODE_OFF
                        : repeatMode == 1 ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_ONE);
            }
            updatePlayIcons(controller != null && controller.isPlaying());
            toast(repeatMode == 0 ? "Repeat mati" : repeatMode == 1 ? "Repeat semua" : "Repeat satu lagu");
        });
        controls.addView(btnLoop);
        c.addView(controls);

        LinearLayout volRow = row();
        volRow.setPadding(0, dp(6), 0, 0);
        btnMute = text("🔊", 16f, C_INK, false);
        btnMute.setGravity(Gravity.CENTER);
        btnMute.setLayoutParams(lp(dp(36), dp(36)));
        btnMute.setOnClickListener(v -> toggleMute());
        volRow.addView(btnMute);
        volSeek = new SeekBar(this);
        volSeek.setMax(100);
        volSeek.setProgress(Math.round(prefs.getFloat("volume", 1f) * 100));
        volSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) setVolumePercent(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
        volRow.addView(volSeek, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(volRow);

        return c;
    }

    View quickActions() {
        LinearLayout wrap = column();
        LinearLayout r1 = row();
        TextView crateBtn = outlineButton("🗂  The Crate", v -> {
            if (mainScroll != null) mainScroll.smoothScrollTo(0, dp(430));
        });
        TextView posterBtn = outlineButton("🎨  Poster", v -> showPosterDialog());
        crateBtn.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        posterBtn.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r1.addView(crateBtn);
        r1.addView(spacerH(8));
        r1.addView(posterBtn);
        wrap.addView(r1);

        LinearLayout r2 = row();
        TextView reportBtn = outlineButton("📊  Report", v -> showReportDialog());
        TextView lyricsBtn = outlineButton("📝  Lyrics", v -> showLyricsDialog());
        reportBtn.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lyricsBtn.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams r2lp = matchWrap();
        r2lp.topMargin = dp(8);
        r2.setLayoutParams(r2lp);
        r2.addView(reportBtn);
        r2.addView(spacerH(8));
        r2.addView(lyricsBtn);
        wrap.addView(r2);

        TextView addBtn = primaryButton("+  Add Music", v -> showAddMusicDialog());
        LinearLayout.LayoutParams alp = matchWrap();
        alp.topMargin = dp(12);
        alp.bottomMargin = dp(14);
        addBtn.setLayoutParams(alp);
        wrap.addView(addBtn);
        return wrap;
    }

    View crateCard() {
        LinearLayout c = card(C_PAPER);
        LinearLayout head = row();
        TextView title = text("The Crate", 20f, C_INK, true);
        crateCount = text("0 lagu", 12f, C_MUTED, false);
        head.addView(title, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(crateCount);
        c.addView(head);

        crateSearch = new EditText(this);
        crateSearch.setHint("Cari judul atau artis…");
        crateSearch.setTextSize(14f);
        crateSearch.setSingleLine(true);
        crateSearch.setBackground(bg(Color.WHITE, 16f, 0xFFE3D8C4, 1f));
        crateSearch.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams slp = matchWrap();
        slp.topMargin = dp(10);
        crateSearch.setLayoutParams(slp);
        crateSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                renderCrateList();
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        c.addView(crateSearch);

        crateEmpty = text("Crate masih kosong. Tambahkan lagu pertamamu 🎵", 13f, C_MUTED, false);
        crateEmpty.setPadding(0, dp(16), 0, dp(6));
        c.addView(crateEmpty);

        crateList = column();
        crateList.setPadding(0, dp(8), 0, 0);
        c.addView(crateList);
        return c;
    }

    View top5Card() {
        LinearLayout c = card(C_PAPER_2);
        LinearLayout head = row();
        head.addView(text("Top 5 Song of the Month", 18f, C_INK, true),
                lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(label(new SimpleDateFormat("MMMM yyyy", new Locale("id", "ID"))
                .format(new Date())));
        c.addView(head);
        top5List = column();
        top5List.setPadding(0, dp(10), 0, 0);
        c.addView(top5List);
        return c;
    }

    void renderDashboard() {
        renderCrateList();
        renderTop5();
        updatePlayerUi();
        updateMiniBar();
        refreshAppBar();
    }

    void renderCrateList() {
        if (crateList == null) return;
        crateList.removeAllViews();
        String q = crateSearch == null ? "" : crateSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Track t : crate) {
            if (!q.isEmpty() && !t.title.toLowerCase(Locale.ROOT).contains(q)
                    && !t.artist.toLowerCase(Locale.ROOT).contains(q)) continue;
            crateList.addView(trackRow(t, false));
            shown++;
        }
        if (crateCount != null) crateCount.setText(crate.size() + " lagu");
        if (crateEmpty != null) {
            crateEmpty.setVisibility(crate.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (shown == 0 && !crate.isEmpty()) {
            TextView t = text("Tidak ada lagu yang cocok dengan \"" + q + "\".", 13f, C_MUTED, false);
            t.setPadding(0, dp(10), 0, 0);
            crateList.addView(t);
        }
    }

    View trackRow(Track t, boolean compact) {
        LinearLayout r = row();
        r.setPadding(0, dp(6), 0, dp(6));
        r.setBackground(bg(Color.TRANSPARENT, 14f, 0, 0));

        ImageView cover = new ImageView(this);
        int size = compact ? dp(38) : dp(46);
        cover.setLayoutParams(lp(size, size));
        GradientDrawable ph = new GradientDrawable();
        ph.setCornerRadius(dp(10));
        ph.setColor(Color.parseColor(t.vinylColor));
        cover.setBackground(ph);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        applyCover(cover, t);
        r.addView(cover);

        LinearLayout texts = column();
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(t.title, 15f, C_INK, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView sub = text(t.artist, 12f, C_MUTED, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        texts.addView(sub);
        r.addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView play = text("▶", 16f, C_RED, false);
        play.setPadding(dp(10), dp(8), dp(10), dp(8));
        play.setOnClickListener(v -> playTrack(t));
        r.addView(play);

        TextView menu = text("⋮", 20f, C_MUTED, false);
        menu.setPadding(dp(8), dp(6), dp(10), dp(6));
        menu.setOnClickListener(v -> showTrackMenu(t));
        r.addView(menu);

        r.setClickable(true);
        r.setOnClickListener(v -> playTrack(t));
        return r;
    }

    void showTrackMenu(Track t) {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 22f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.addView(text(t.title, 18f, C_INK, true));
        c.addView(text(t.artist, 13f, C_MUTED, false));
        TextView info = text(t.local ? "Lokal · " + (TextUtils.isEmpty(t.audioUrl) ? "file perangkat" : "URI perangkat")
                : (t.audioPath.isEmpty() ? "Cloud · URL eksternal" : "Cloud · " + t.audioPath), 11f, C_MUTED, false);
        info.setPadding(0, dp(6), 0, dp(10));
        c.addView(info);

        TextView playNow = primaryButton("Play", v -> {
            d.dismiss();
            playTrack(t);
        });
        c.addView(playNow, matchWrap());
        TextView remove = subtleButton("Remove from crate", v -> {
            d.dismiss();
            confirm("Hapus lagu?", "Hapus \"" + t.title + "\" dari crate?"
                    + (t.local ? "" : " File di Supabase Storage juga akan dihapus."), "Hapus",
                    () -> deleteTrack(t));
        });
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(8);
        remove.setLayoutParams(rlp);
        c.addView(remove);
        TextView close = subtleButton("Close", v -> d.dismiss());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(8);
        close.setLayoutParams(clp);
        c.addView(close);

        d.setContentView(c);
        showDialog(d);
    }

    void applyCover(ImageView iv, Track t) {
        if (iv == null) return;
        // Guest-mode tracks point at persisted content: URIs, never remote URLs.
        if (TextUtils.isEmpty(t.coverUrl) || !isFetchableArtwork(t.coverUrl)) {
            iv.setImageDrawable(null);
            iv.setTag(null);
            return;
        }
        Bitmap cached = bmpCache.get(t.coverUrl);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(t.coverUrl);
        final String url = t.coverUrl;
        runIo(() -> {
            Bitmap bmp = fetchBitmap(url);
            if (bmp == null) return;
            synchronized (bmpCache) {
                if (bmpCache.size() > 48) bmpCache.clear();
                bmpCache.put(url, bmp);
            }
            runUi(() -> {
                if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
            });
        });
    }

    /** Only http(s) or device content can be loaded; anything else (e.g. a pasted jellyfin URL
     *  with a weird scheme) is ignored instead of crashing. */
    boolean isFetchableArtwork(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                    || "content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    @Nullable
    Bitmap fetchBitmap(String urlStr) {
        if (TextUtils.isEmpty(urlStr)) return null;
        if (urlStr.startsWith("content:") || urlStr.startsWith("file:")) {
            try (InputStream in = getContentResolver().openInputStream(Uri.parse(urlStr))) {
                return BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                return null;
            }
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            try (InputStream in = conn.getInputStream()) {
                byte[] data = readStreamBytes(in);
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static byte[] readStreamBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    void updatePlayerUi() {
        Track t = currentTrack();
        if (npTitle != null) {
            npTitle.setText(t == null ? "No record loaded" : t.title);
            npArtist.setText(t == null
                    ? (crate.isEmpty() ? "Tambahkan lagu ke crate untuk mulai" : "Pilih lagu dari crate di bawah")
                    : t.artist);
            npEyebrow.setText(t == null ? "SEKARANG DIPUTAR" : (t.local ? "LOKAL · SEKARANG DIPUTAR"
                    : "CLOUD · SEKARANG DIPUTAR"));
            if (vinyl != null) {
                vinyl.setColors(t == null ? DEFAULT_VINYL : t.vinylColor,
                        t == null ? DEFAULT_LABEL : t.labelColor);
                vinyl.label = t == null ? "CHIROOT" : t.artist.toUpperCase(Locale.ROOT);
                vinyl.setArt(null);
                if (t != null && !TextUtils.isEmpty(t.coverUrl)) {
                    Bitmap cached = bmpCache.get(t.coverUrl);
                    if (cached != null) vinyl.setArt(cached);
                    else {
                        final String url = t.coverUrl;
                        runIo(() -> {
                            Bitmap b = fetchBitmap(url);
                            if (b != null) {
                                synchronized (bmpCache) {
                                    bmpCache.put(url, b);
                                }
                                runUi(() -> {
                                    Track cur = currentTrack();
                                    if (cur != null && url.equals(cur.coverUrl) && vinyl != null) {
                                        vinyl.setArt(b);
                                    }
                                });
                            }
                        });
                    }
                }
                vinyl.invalidate();
            }
        }
        updateSeekProgress();
        updatePlayIcons(controller != null && controller.isPlaying());
    }

    void updateSeekProgress() {
        if (controller == null) return;
        long pos = Math.max(0, controller.getCurrentPosition());
        long dur = controller.getDuration();
        if (dur <= 0) dur = currentTrack() == null ? 0 : currentTrack().durationMs;
        if (npSeek != null) {
            if (dur > 0) npSeek.setProgress((int) (pos * 1000 / dur));
            else npSeek.setProgress(0);
        }
        if (npCur != null) npCur.setText(humanTime(pos));
        if (npDur != null) npDur.setText(dur > 0 ? humanTime(dur) : "0:00");
        if (miniProgress != null && dur > 0) miniProgress.setProgress((int) (pos * 1000 / dur));
    }

    void updatePlayIcons(boolean playing) {
        if (btnPlayIcon != null) btnPlayIcon.setText(playing ? "⏸" : "▶");
        if (miniPlayIcon != null) miniPlayIcon.setText(playing ? "⏸" : "▶");
        if (btnShuffle != null) btnShuffle.setTextColor(shuffleOn ? C_RED : C_MUTED);
        if (btnLoop != null) {
            btnLoop.setText(repeatMode == 2 ? "🔂" : "↻");
            btnLoop.setTextColor(repeatMode == 0 ? C_MUTED : C_RED);
        }
        if (btnMute != null && controller != null) {
            btnMute.setText(controller.getVolume() == 0f ? "🔇" : "🔊");
        }
        if (vinyl != null) vinyl.setPlaying(playing);
    }

    void updateMiniBar() {
        if (miniBar == null) return;
        Track t = currentTrack();
        if (t == null) {
            miniBar.setVisibility(View.GONE);
            miniProgress.setVisibility(View.GONE);
            return;
        }
        miniBar.setVisibility(View.VISIBLE);
        miniProgress.setVisibility(View.VISIBLE);
        miniTitle.setText(t.title);
        miniArtist.setText(t.artist);
        miniPlayIcon.setText(controller != null && controller.isPlaying() ? "⏸" : "▶");
        applyCover(miniCover, t);
    }

    void syncNowPlayingFromPlayer() {
        if (controller == null) return;
        MediaItem item = controller.getCurrentMediaItem();
        if (item != null && item.mediaId != null) currentTrackId = item.mediaId;
        updatePlayerUi();
    }

    void startTicker() {
        if (ticker != null) return;
        ticker = new Runnable() {
            @Override
            public void run() {
                updateSeekProgress();
                updateMiniBar();
                ui.postDelayed(this, 700);
            }
        };
        ui.postDelayed(ticker, 700);
    }

    /* =====================================================================
     * 16. TOP 5 / LISTENING REPORT
     * ===================================================================== */

    Map<String, Integer> playCounts(@Nullable String monthFilter) {
        Map<String, Integer> counts = new HashMap<>();
        for (Hist h : history) {
            if (monthFilter != null) {
                String key = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date(h.playedAt));
                if (!monthFilter.equals(key)) continue;
            }
            String k = TextUtils.isEmpty(h.trackId) ? h.title : h.trackId;
            counts.put(k, counts.containsKey(k) ? counts.get(k) + 1 : 1);
        }
        return counts;
    }

    void renderTop5() {
        if (top5List == null) return;
        top5List.removeAllViews();
        Map<String, Integer> counts = playCounts(monthKey());
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        if (entries.isEmpty()) {
            top5List.addView(text("Belum ada data putar bulan ini. Putar beberapa lagu dulu 🎧",
                    13f, C_MUTED, false));
            return;
        }
        int rank = 1;
        for (Map.Entry<String, Integer> e : entries) {
            if (rank > 5) break;
            Track t = findTrack(e.getKey());
            String title = t != null ? t.title : titleFromHistory(e.getKey());
            String artist = t != null ? t.artist : "";
            LinearLayout r = row();
            r.setPadding(0, dp(5), 0, dp(5));
            TextView num = text(String.valueOf(rank), 16f, C_RED, true);
            num.setLayoutParams(lp(dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(num);
            LinearLayout texts = column();
            texts.addView(text(title, 14f, C_INK, true));
            if (!artist.isEmpty()) texts.addView(text(artist, 11f, C_MUTED, false));
            r.addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(text(e.getValue() + "×", 13f, C_MUTED, true));
            top5List.addView(r);
            rank++;
        }
    }

    String titleFromHistory(String idOrTitle) {
        for (Hist h : history) if (idOrTitle.equals(h.trackId)) return h.title;
        return idOrTitle;
    }

    void showReportDialog() {
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(18), dp(18), dp(18), dp(18));

        c.addView(text("Listening Report", 22f, C_INK, true));
        c.addView(text("Data asli dari riwayat putar kamu", 12f, C_MUTED, false));

        long total = 0;
        for (Hist h : history) total += h.durationMs;
        int sessions = history.size();

        LinearLayout stats = row();
        stats.setPadding(0, dp(14), 0, dp(6));
        stats.addView(statBox("Total waktu", humanHours(total)), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stats.addView(spacerH(8));
        stats.addView(statBox("Total putar", String.valueOf(sessions)), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(stats);

        Calendar cal = Calendar.getInstance();
        String[] names = {"Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des"};
        LinearLayout months = row();
        months.setPadding(0, dp(10), 0, dp(10));
        for (int i = 0; i < 6; i++) {
            String key = String.format(Locale.US, "%04d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
            final String keyFinal = key;
            int n = playCounts(key).values().stream().mapToInt(Integer::intValue).sum();
            LinearLayout box = column();
            box.setGravity(Gravity.CENTER);
            box.addView(text(String.valueOf(n), 15f, n > 0 ? C_INK : C_MUTED, true));
            box.addView(text(names[cal.get(Calendar.MONTH)], 10f, C_MUTED, false));
            LinearLayout.LayoutParams blp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            box.setLayoutParams(blp);
            box.setOnClickListener(v -> {
                toast("Bulan " + keyFinal + ": " + playCounts(keyFinal).values().stream()
                        .mapToInt(Integer::intValue).sum() + " putar");
            });
            months.addView(box);
            cal.add(Calendar.MONTH, -1);
        }
        c.addView(months);

        c.addView(label("LAGU PALING SERING DIPUTAR"));
        Map<String, Integer> counts = playCounts(null);
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());
        int shown = 0;
        for (Map.Entry<String, Integer> e : entries) {
            if (shown >= 8) break;
            LinearLayout r = row();
            r.setPadding(0, dp(4), 0, dp(4));
            r.addView(text("•", 14f, C_GOLD, true));
            r.addView(text(titleFromHistory(e.getKey()), 14f, C_INK, false),
                    lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(text(e.getValue() + "×", 13f, C_MUTED, true));
            c.addView(r);
            shown++;
        }
        if (shown == 0) {
            c.addView(text("Belum ada riwayat putar.", 13f, C_MUTED, false));
        }

        ScrollView sv = new ResponsiveScrollView(this);
        sv.addView(c);
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout wrap = column();
        wrap.setPadding(dp(12), dp(12), dp(12), dp(12));
        wrap.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView close = subtleButton("Close", v -> d.dismiss());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(10);
        close.setLayoutParams(clp);
        wrap.addView(close);
        d.setContentView(wrap);
        showDialog(d);
    }

    LinearLayout statBox(String title, String value) {
        LinearLayout b = column();
        b.setBackground(bg(Color.WHITE, 16f, 0xFFE3D8C4, 1f));
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        b.addView(text(value, 20f, C_INK, true));
        b.addView(text(title, 11f, C_MUTED, false));
        return b;
    }

    /* =====================================================================
     * 17. GATE, DIALOG PLUMBING
     * ===================================================================== */

    void showDialog(Dialog d) {
        if (activeDialog != null && activeDialog.isShowing() && activeDialog != d) {
            activeDialog.dismiss();
        }
        activeDialog = d;
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        d.setOnDismissListener(x -> {
            if (activeDialog == d) activeDialog = null;
        });
        d.show();
        if (w != null) {
            int width = Math.min(dp(560), getResources().getDisplayMetrics().widthPixels - dp(24));
            w.setLayout(Math.max(dp(260), width), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    void closeDialogs() {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        activeDialog = null;
    }

    View.OnClickListener closeActive() {
        return v -> {
            if (activeDialog != null) activeDialog.dismiss();
        };
    }

    /** Simple text prompt dialog (title, message, primary action). */
    void info(String title, String message, String button, @Nullable Runnable onOk) {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(20), dp(20), dp(20), dp(18));
        c.addView(text(title, 20f, C_INK, true));
        TextView m = text(message, 14f, C_INK_SOFT, false);
        m.setPadding(0, dp(8), 0, dp(16));
        c.addView(m);
        c.addView(primaryButton(button, v -> {
            d.dismiss();
            if (onOk != null) onOk.run();
        }), matchWrap());
        d.setContentView(c);
        showDialog(d);
    }

    void confirm(String title, String message, String confirmLabel, Runnable onConfirm) {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(20), dp(20), dp(20), dp(18));
        c.addView(text(title, 20f, C_INK, true));
        TextView m = text(message, 14f, C_INK_SOFT, false);
        m.setPadding(0, dp(8), 0, dp(16));
        c.addView(m);
        c.addView(primaryButton(confirmLabel, v -> {
            d.dismiss();
            onConfirm.run();
        }), matchWrap());
        TextView cancel = subtleButton("Cancel", v -> d.dismiss());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(8);
        cancel.setLayoutParams(clp);
        c.addView(cancel);
        d.setContentView(c);
        showDialog(d);
    }

    EditText styledInput(String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(15f);
        e.setInputType(inputType);
        e.setBackground(bg(Color.WHITE, 14f, 0xFFE3D8C4, 1f));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        e.setLayoutParams(lp);
        return e;
    }

    /** Quick sheet with account/login plus a path into Settings while in guest mode. */
    void showWelcomeSheet() {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 26f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(22), dp(22), dp(22), dp(18));

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.chiroot_mark);
        mark.setLayoutParams(lp(dp(72), dp(72)));
        c.addView(mark);
        c.addView(text("Chiroot", 24f, C_INK, true));
        c.addView(text(guestMode ? "Mode tamu · koleksi lokal"
                : "Masuk sebagai " + (userName.isEmpty() ? userEmail : userName), 13f, C_MUTED, false));

        if (guestMode) {
            TextView login = primaryButton("Login dengan Google", v -> {
                d.dismiss();
                startGoogleOAuth();
            });
            LinearLayout.LayoutParams llp = matchWrap();
            llp.topMargin = dp(14);
            login.setLayoutParams(llp);
            c.addView(login);
        } else {
            TextView prof = primaryButton("Buka profile", v -> {
                d.dismiss();
                showProfileDialog();
            });
            LinearLayout.LayoutParams llp = matchWrap();
            llp.topMargin = dp(14);
            prof.setLayoutParams(llp);
            c.addView(prof);
        }

        TextView settings = outlineButton("Settings", v -> {
            d.dismiss();
            showSettingsDialog();
        });
        LinearLayout.LayoutParams slp = matchWrap();
        slp.topMargin = dp(8);
        settings.setLayoutParams(slp);
        c.addView(settings);

        TextView close = subtleButton("Close", v -> d.dismiss());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(8);
        close.setLayoutParams(clp);
        c.addView(close);

        d.setContentView(c);
        showDialog(d);
    }

    /* ---- auth gate: shown whenever nobody is signed in ---- */
    void showAuthGate() {
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 28f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(24), dp(28), dp(24), dp(24));
        c.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.chiroot_mark);
        mark.setLayoutParams(lp(dp(92), dp(92)));
        c.addView(mark);

        TextView title = text("Chiroot", 30f, C_INK, true);
        title.setPadding(0, dp(12), 0, 0);
        c.addView(title);
        TextView sub = text("Pemutar musik gratis, tanpa iklan.\nTampilan gramofon klasik untuk koleksimu.",
                14f, C_MUTED, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(6), 0, dp(22));
        c.addView(sub);

        TextView google = primaryButton("Lanjutkan dengan Google", v -> startGoogleOAuth());
        c.addView(google, matchWrap());

        TextView guest = outlineButton("Masuk sebagai Tamu", v -> {
            guestMode = true;
            closeDialogs();
            refreshAppBar();
            renderDashboard();
            toast("Mode tamu aktif — koleksi disimpan di perangkat.");
        });
        LinearLayout.LayoutParams glp = matchWrap();
        glp.topMargin = dp(10);
        guest.setLayoutParams(glp);
        c.addView(guest);

        TextView note = text("Mode tamu menyimpan lagu hanya di perangkat ini. "
                + "Login Google mengaktifkan The Crate cloud, laporan, dan profil.",
                11f, C_MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, dp(16), 0, 0);
        c.addView(note);

        TextView settingsLink = text("Settings", 13f, C_WOOD, true);
        settingsLink.setGravity(Gravity.CENTER);
        settingsLink.setPadding(0, dp(14), 0, 0);
        c.addView(settingsLink);

        Dialog d = new Dialog(this, R.style.ChirootDialog);
        d.setCancelable(false);
        settingsLink.setOnClickListener(v -> showSettingsDialog());
        d.setContentView(c);
        showDialog(d);
        showDashboard();
    }

    /* =====================================================================
     * 18. CONNECTION / SERVER ERROR MODAL
     *
     * One reusable component. Uses the supplied illustration exactly as provided
     * (app/src/main/res/drawable-nodpi/chiroot_error_sign.png) whose baked-in text is
     * "something went wrong." / "Pls try again later alright :>" — the artwork is never
     * redrawn or re-lettered.
     *
     * Only connection-class failures use this modal: offline, timeout, 5xx, failed
     * upload/download. Validation errors, wrong passwords, cancelled OAuth and other
     * expected user actions get inline messages instead.
     * ===================================================================== */

    void showConnectionError(String detail, @Nullable Runnable retryAction) {
        runUi(() -> {
            if (isFinishing() || isDestroyed()) return;
            // Update BOTH the message and the operation. A stale retry from an earlier
            // failure must never upload/save the wrong thing.
            errorRetry = retryAction;
            if (errorDialog != null && errorDialog.isShowing()) {
                TextView existing = errorDialog.findViewById(R.id.error_detail);
                TextView button = errorDialog.findViewById(R.id.error_retry);
                if (existing != null) existing.setText(detail);
                if (button != null) button.setEnabled(true);
                return;
            }
            if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
            errorDialog = buildErrorDialog(detail);
            errorDialog.setOnDismissListener(dialog -> {
                if (errorDialog == dialog) {
                    errorDialog = null;
                    errorRetry = null;
                }
            });
            errorDialog.show();
            Window window = errorDialog.getWindow();
            if (window != null) {
                int width = Math.min(dp(520), getResources().getDisplayMetrics().widthPixels - dp(24));
                window.setLayout(Math.max(dp(260), width), ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setGravity(Gravity.CENTER);
            }
        });
    }

    Dialog buildErrorDialog(String detail) {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        d.setCancelable(true);
        LinearLayout c = column();
        c.setId(R.id.error_card);
        c.setBackground(bg(C_PAPER, 26f, C_INK, 1.5f));
        c.setPadding(dp(18), dp(18), dp(18), dp(16));

        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.chiroot_error_sign);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setAdjustViewBounds(true);
        art.setMaxHeight(dp(190));
        art.setContentDescription("something went wrong. Pls try again later alright :>");
        LinearLayout.LayoutParams artLp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        artLp.bottomMargin = dp(8);
        c.addView(art, artLp);

        TextView det = text(detail, 14f, C_INK_SOFT, false);
        det.setId(R.id.error_detail);
        det.setGravity(Gravity.CENTER);
        det.setPadding(0, dp(4), 0, dp(4));
        c.addView(det);

        TextView retry = primaryButton("Try Again", v -> {
            Runnable again = errorRetry;
            if (again == null || !v.isEnabled()) return;
            v.setEnabled(false);
            errorRetry = null;
            d.dismiss();
            again.run();
        });
        retry.setId(R.id.error_retry);
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(12);
        c.addView(retry, rlp);
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(8);
        c.addView(subtleButton("Close", v -> d.dismiss()), clp);

        ResponsiveScrollView scroll = new ResponsiveScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(c);
        d.setContentView(scroll);
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawableResource(android.R.color.transparent);
        return d;
    }

    /** Connection failures get the illustration; expected errors get a specific message. */
    void showOperationError(Throwable error, String operation, Runnable retry) {
        if (error instanceof ApiException && ((ApiException) error).isConnectionProblem()) {
            showConnectionError(operation + ". " + describe(error), retry);
        } else if (error instanceof ApiException) {
            ApiException e = (ApiException) error;
            String message;
            switch (e.kind) {
                case "auth": message = "Sesi berakhir. Silakan login lagi."; break;
                case "permission": message = "Akses ditolak. Periksa izin akun dan kebijakan Supabase."; break;
                default: message = "Data belum bisa disimpan. Periksa input dan konfigurasi backend."; break;
            }
            toast(operation + ". " + message);
        } else {
            // Local file/JSON errors are not evidence that the internet is down.
            toast(operation + ". Periksa file dan data yang dipilih.");
        }
    }

    /** Wraps an IO operation with the standard offline check + connection modal. */
    void withNetwork(String what, Runnable op) {
        if (!online()) {
            showConnectionError("Butuh koneksi internet untuk " + what + ".", op);
            return;
        }
        op.run();
    }

    /* =====================================================================
     * 19. ADD MUSIC DIALOG + QUICK AUTO-FILL
     * ===================================================================== */

    static final String[] LABEL_SWATCHES = {"#D84A3A", "#C9A227", "#3A8A6A", "#5B6EE1", "#B65A9A", "#D28A3A"};
    static final String[] VINYL_SWATCHES = {"#1E1E24", "#2B2B33", "#3A2418", "#14262B", "#2A1430", "#101010"};

    String draftLabel = DEFAULT_LABEL;
    String draftVinyl = DEFAULT_VINYL;

    void showAddMusicDialog() {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        ScrollView sv = new ResponsiveScrollView(this);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 26f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(18), dp(18), dp(18), dp(18));

        c.addView(text("Add Music", 22f, C_INK, true));
        c.addView(text(guestMode ? "Mode tamu: file tetap di perangkat ini"
                : "Upload ke Supabase Storage + The Crate cloud", 12f, C_MUTED, false));

        c.addView(label("FILE AUDIO (MP3)"));
        TextView pickAudioBtn = outlineButton(
                pendingAudioUri == null ? "Pilih file MP3…" : "✓ " + trim(queryDisplayName(pendingAudioUri), 34),
                v -> pickAudio());
        LinearLayout.LayoutParams plp = matchWrap();
        plp.topMargin = dp(6);
        pickAudioBtn.setLayoutParams(plp);
        c.addView(pickAudioBtn);

        c.addView(label("JUDUL"));
        EditText titleIn = styledInput("Judul lagu", "", InputType.TYPE_CLASS_TEXT);
        c.addView(titleIn);
        c.addView(label("ARTIS"));
        EditText artistIn = styledInput("Nama artis", "", InputType.TYPE_CLASS_TEXT);
        c.addView(artistIn);

        TextView autofill = subtleButton("✨ Quick Auto-fill dari nama file", v -> {
            String name = pendingAudioUri == null ? "" : queryDisplayName(pendingAudioUri);
            name = name.replaceAll("(?i)\\.(mp3|m4a|wav|ogg|flac)$", "");
            String[] parts = name.split("\\s*[-–]\\s*", 2);
            if (parts.length == 2) {
                artistIn.setText(parts[0].trim());
                titleIn.setText(parts[1].trim());
            } else {
                titleIn.setText(name.trim());
            }
            toast("Judul dan artis diisi dari nama file. Periksa sebelum simpan.");
        });
        LinearLayout.LayoutParams alfp = matchWrap();
        alfp.topMargin = dp(10);
        autofill.setLayoutParams(alfp);
        c.addView(autofill);

        c.addView(label("COVER ARTWORK"));
        TextView pickCoverBtn = outlineButton(
                pendingCoverUri == null ? "Pilih gambar cover…" : "✓ " + trim(queryDisplayName(pendingCoverUri), 34),
                v -> pickCover());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(6);
        pickCoverBtn.setLayoutParams(clp);
        c.addView(pickCoverBtn);

        c.addView(label("WARNA LABEL VINYL"));
        c.addView(swatchRow(LABEL_SWATCHES, true));
        c.addView(label("WARNA PIRINGAN HITAM"));
        c.addView(swatchRow(VINYL_SWATCHES, false));

        uploadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        uploadBar.setMax(100);
        uploadBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = matchWrap();
        blp.topMargin = dp(12);
        uploadBar.setLayoutParams(blp);
        c.addView(uploadBar);

        uploadLabel = text("", 11f, C_MUTED, false);
        uploadLabel.setPadding(0, dp(6), 0, 0);
        c.addView(uploadLabel);

        TextView submit = primaryButton("Save to Crate", v -> {
            String title = titleIn.getText().toString().trim();
            String artist = artistIn.getText().toString().trim();
            if (title.isEmpty()) {
                titleIn.setError("Judul wajib diisi");
                return;
            }
            if (artist.isEmpty()) artist = "Unknown Artist";
            if (pendingAudioUri == null) {
                toast("Pilih file MP3 dulu.");
                return;
            }
            try {
                JSONObject fields = new JSONObject();
                fields.put("title", trim(title, 80));
                fields.put("artist", trim(artist, 80));
                fields.put("label", draftLabel);
                fields.put("vinyl", draftVinyl);
                commitAddMusic(fields, pendingAudioUri, pendingCoverUri);
                showUploadProgressInline();
                uploadLabel.setText("Mengunggah…");
            } catch (JSONException e) {
                toast("Data tidak valid.");
            }
        });
        LinearLayout.LayoutParams slp = matchWrap();
        slp.topMargin = dp(14);
        submit.setLayoutParams(slp);
        c.addView(submit);

        TextView close = subtleButton("Close", v -> {
            d.dismiss();
            pendingAudioUri = null;
            pendingCoverUri = null;
        });
        LinearLayout.LayoutParams closeLp = matchWrap();
        closeLp.topMargin = dp(8);
        close.setLayoutParams(closeLp);
        c.addView(close);

        sv.addView(c);
        LinearLayout wrap = column();
        wrap.setPadding(dp(10), dp(10), dp(10), dp(10));
        wrap.addView(sv, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setContentView(wrap);
        showDialog(d);
    }

    void showUploadProgressInline() {
        // Progress lives inside the Add Music sheet; nothing to do here beyond visibility.
        if (uploadBar != null) uploadBar.setVisibility(View.VISIBLE);
    }

    static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    View swatchRow(String[] colors, boolean isLabel) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = row();
        r.setPadding(0, dp(6), 0, dp(4));
        for (String hex : colors) {
            View v = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.parseColor(hex));
            g.setStroke(dp(2), (isLabel ? draftLabel : draftVinyl).equalsIgnoreCase(hex) ? C_INK : 0x00000000);
            v.setBackground(g);
            LinearLayout.LayoutParams vlp = lp(dp(34), dp(34));
            vlp.rightMargin = dp(10);
            v.setLayoutParams(vlp);
            v.setOnClickListener(x -> {
                if (isLabel) draftLabel = hex;
                else draftVinyl = hex;
                if (vinyl != null) vinyl.setColors(draftVinyl, draftLabel);
                toast("Warna dipilih: " + hex);
            });
            r.addView(v);
        }
        hs.addView(r);
        return hs;
    }

    /* =====================================================================
     * 20. POSTER GENERATOR — Android Canvas + hand-written standalone SVG
     * ===================================================================== */

    @Nullable
    Bitmap renderPoster(@Nullable Track t, boolean withCover) {
        int w = 1080, h = 1350;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // paper background
        c.drawColor(C_PAPER);
        p.setColor(0x14000000);
        for (int i = 0; i < h; i += 4) c.drawRect(0, i, w, i + 1, p);

        // frame
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6f);
        p.setColor(C_INK);
        RectF frame = new RectF(40, 40, w - 40, h - 40);
        c.drawRoundRect(frame, 28, 28, p);
        p.setStyle(Paint.Style.FILL);

        // header
        p.setColor(C_INK);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(64f);
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText("CHIROOT", 80, 150, p);
        p.setTextSize(26f);
        p.setColor(C_MUTED);
        c.drawText("now spinning", 80, 192, p);

        // vinyl
        float cx = w / 2f, cy = 560, r = 300;
        p.setColor(Color.parseColor(t == null ? DEFAULT_VINYL : safeColor(t.vinylColor, DEFAULT_VINYL)));
        c.drawCircle(cx, cy, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f);
        for (int i = 1; i <= 10; i++) {
            p.setColor(i % 2 == 0 ? 0x22FFFFFF : 0x16000000);
            c.drawCircle(cx, cy, r * (0.40f + i * 0.055f), p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor(t == null ? DEFAULT_LABEL : safeColor(t.labelColor, DEFAULT_LABEL)));
        c.drawCircle(cx, cy, r * 0.33f, p);

        Bitmap cov = null;
        if (withCover && t != null && !TextUtils.isEmpty(t.coverUrl)) {
            cov = bmpCache.get(t.coverUrl);
            if (cov == null) cov = fetchBitmap(t.coverUrl);
            if (cov != null) {
                synchronized (bmpCache) {
                    bmpCache.put(t.coverUrl, cov);
                }
            }
        }
        if (cov != null) {
            Path clip = new Path();
            clip.addCircle(cx, cy, r * 0.32f, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(cov, null, new RectF(cx - r * 0.33f, cy - r * 0.33f, cx + r * 0.33f, cy + r * 0.33f), p);
            c.restore();
        } else {
            p.setColor(0xFFF4EFDF);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            p.setTextSize(44f);
            c.drawText("CHIROOT", cx, cy + 14, p);
        }
        p.setColor(0xFFF4EFDF);
        c.drawCircle(cx, cy, 10, p);

        // title + artist
        p.setTextAlign(Paint.Align.CENTER);
        p.setColor(C_INK);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(56f);
        String title = t == null ? "No record loaded" : t.title;
        if (title.length() > 24) title = title.substring(0, 23) + "…";
        c.drawText(title, w / 2f, 1000, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(36f);
        p.setColor(C_MUTED);
        String artist = t == null ? "chiroot.my.id" : t.artist;
        if (artist.length() > 30) artist = artist.substring(0, 29) + "…";
        c.drawText(artist, w / 2f, 1056, p);

        // footer
        p.setTextSize(24f);
        p.setColor(C_WOOD);
        c.drawText("gramophone sessions · chiroot.my.id", w / 2f, h - 90, p);
        return bmp;
    }

    String posterSvg(@Nullable Track t, boolean withCover) {
        String title = escapeXml(t == null ? "No record loaded" : t.title);
        String artist = escapeXml(t == null ? "chiroot.my.id" : t.artist);
        String vinyl = t == null ? DEFAULT_VINYL : safeColor(t.vinylColor, DEFAULT_VINYL);
        String lab = t == null ? DEFAULT_LABEL : safeColor(t.labelColor, DEFAULT_LABEL);
        StringBuilder rings = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            rings.append(String.format(Locale.US,
                    "  <circle cx=\"540\" cy=\"560\" r=\"%.1f\" fill=\"none\" stroke=\"#ffffff\" stroke-opacity=\"0.10\" stroke-width=\"2\"/>\n",
                    300 * (0.40 + i * 0.055)));
        }
        String cover = "";
        if (withCover && t != null && !TextUtils.isEmpty(t.coverUrl)) {
            cover = "  <image href=\"" + escapeXml(t.coverUrl) + "\" x=\"440\" y=\"460\" width=\"200\" height=\"200\" "
                    + "clip-path=\"url(#labelClip)\" preserveAspectRatio=\"xMidYMid slice\"/>\n";
        } else {
            cover = "  <text x=\"540\" y=\"572\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"40\" "
                    + "font-weight=\"bold\" fill=\"#F4EFDF\">CHIROOT</text>\n";
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" "
                + "width=\"1080\" height=\"1350\" viewBox=\"0 0 1080 1350\">\n"
                + "  <defs>\n"
                + "    <clipPath id=\"labelClip\"><circle cx=\"540\" cy=\"560\" r=\"66\"/></clipPath>\n"
                + "    <radialGradient id=\"sheen\" cx=\"35%\" cy=\"30%\" r=\"80%\">\n"
                + "      <stop offset=\"0%\" stop-color=\"#ffffff\" stop-opacity=\"0.14\"/>\n"
                + "      <stop offset=\"100%\" stop-color=\"#000000\" stop-opacity=\"0.25\"/>\n"
                + "    </radialGradient>\n"
                + "  </defs>\n"
                + "  <rect width=\"1080\" height=\"1350\" fill=\"#F7F3E9\"/>\n"
                + "  <rect x=\"40\" y=\"40\" width=\"1000\" height=\"1270\" rx=\"28\" fill=\"none\" stroke=\"#1A1A1A\" stroke-width=\"6\"/>\n"
                + "  <text x=\"80\" y=\"150\" font-family=\"sans-serif\" font-size=\"64\" font-weight=\"bold\" fill=\"#1A1A1A\">CHIROOT</text>\n"
                + "  <text x=\"80\" y=\"192\" font-family=\"sans-serif\" font-size=\"26\" fill=\"#7B6F5F\">now spinning</text>\n"
                + "  <circle cx=\"540\" cy=\"560\" r=\"300\" fill=\"" + vinyl + "\"/>\n"
                + rings
                + "  <circle cx=\"540\" cy=\"560\" r=\"300\" fill=\"url(#sheen)\"/>\n"
                + "  <circle cx=\"540\" cy=\"560\" r=\"99\" fill=\"" + lab + "\"/>\n"
                + cover
                + "  <circle cx=\"540\" cy=\"560\" r=\"10\" fill=\"#F4EFDF\"/>\n"
                + "  <text x=\"540\" y=\"1000\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"56\" "
                + "font-weight=\"bold\" fill=\"#1A1A1A\">" + title + "</text>\n"
                + "  <text x=\"540\" y=\"1056\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"36\" "
                + "fill=\"#7B6F5F\">" + artist + "</text>\n"
                + "  <text x=\"540\" y=\"1260\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"24\" "
                + "fill=\"#A66B3A\">gramophone sessions · chiroot.my.id</text>\n"
                + "</svg>\n";
    }

    static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    void showPosterDialog() {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.addView(text("Share Now Spinning", 20f, C_INK, true));

        ImageView preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams plp = matchWrap();
        plp.topMargin = dp(10);
        plp.bottomMargin = dp(10);
        preview.setLayoutParams(plp);
        c.addView(preview);

        final boolean[] withCover = {true};
        final Runnable redraw = () -> {
            Track t = currentTrack();
            runIo(() -> {
                Bitmap b = renderPoster(t, withCover[0]);
                runUi(() -> preview.setImageBitmap(b));
            });
        };
        runIo(() -> {
            Bitmap b = renderPoster(currentTrack(), true);
            runUi(() -> preview.setImageBitmap(b));
        });

        LinearLayout r1 = row();
        TextView regen = outlineButton("Redraw", v -> {
            withCover[0] = !withCover[0];
            redraw.run();
            toast(withCover[0] ? "Poster dengan cover" : "Poster tanpa cover");
        });
        TextView svg = outlineButton("Download SVG", v -> {
            pendingSvgForSave = posterSvg(currentTrack(), withCover[0]);
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/svg+xml");
            i.putExtra(Intent.EXTRA_TITLE, "chiroot-poster.svg");
            try {
                startActivityForResult(i, REQ_SAVE_SVG);
            } catch (ActivityNotFoundException e) {
                toast("Tidak ada penyimpan file.");
            }
        });
        regen.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        svg.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r1.addView(regen);
        r1.addView(spacerH(8));
        r1.addView(svg);
        c.addView(r1);

        LinearLayout r2 = row();
        TextView share = primaryButton("Share", v -> {
            Track t = currentTrack();
            runIo(() -> {
                Bitmap b = renderPoster(t, withCover[0]);
                runUi(() -> shareBitmap(b));
            });
        });
        TextView copy = subtleButton("Copy", v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Chiroot",
                        "Now spinning: " + (currentTrack() == null ? "Chiroot" : currentTrack().title
                                + " — " + currentTrack().artist) + "\n#Chiroot"));
                toast("Teks poster dicopy.");
            }
        });
        share.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        copy.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams r2lp = matchWrap();
        r2lp.topMargin = dp(8);
        r2.setLayoutParams(r2lp);
        r2.addView(share);
        r2.addView(spacerH(8));
        r2.addView(copy);
        c.addView(r2);

        TextView close = subtleButton("Close", v -> d.dismiss());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(8);
        close.setLayoutParams(clp);
        c.addView(close);

        ScrollView sv = new ResponsiveScrollView(this);
        sv.addView(c);
        d.setContentView(sv);
        showDialog(d);
    }

    void shareBitmap(Bitmap bmp) {
        try {
            File dir = new File(getCacheDir(), "posters");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("cache");
            File f = new File(dir, "chiroot-poster-" + System.currentTimeMillis() + ".png");
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/png");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_TEXT, "Now spinning di Chiroot 🎧");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share poster"));
        } catch (Exception e) {
            toast("Gagal membagikan poster: " + e.getMessage());
        }
    }

    /* =====================================================================
     * 21. LYRICS — real lookups only (LRCLIB), never invented text
     * ===================================================================== */

    void showLyricsDialog() {
        Track t = currentTrack();
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(18), dp(18), dp(18), dp(16));
        c.addView(text("Lyrics", 22f, C_INK, true));
        TextView sub = text(t == null ? "Tidak ada lagu yang diputar" : t.title + " · " + t.artist,
                12f, C_MUTED, false);
        c.addView(sub);

        ScrollView sv = new ResponsiveScrollView(this);
        TextView body = text("Memuat lirik…", 15f, C_INK_SOFT, false);
        body.setLineSpacing(dp(5), 1f);
        body.setPadding(0, dp(12), 0, dp(12));
        sv.addView(body);
        LinearLayout.LayoutParams slp = matchWrap();
        slp.topMargin = dp(6);
        slp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        sv.setLayoutParams(slp);
        c.addView(sv);

        TextView refresh = subtleButton("Refresh", v -> {
            if (t == null) {
                body.setText("Putar lagu dulu untuk melihat liriknya.");
                return;
            }
            body.setText("Memuat lirik…");
            fetchLyrics(t, body);
        });
        c.addView(refresh, matchWrap());
        TextView close = primaryButton("Close", v -> d.dismiss());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(8);
        close.setLayoutParams(clp);
        c.addView(close);

        d.setContentView(c);
        showDialog(d);

        if (t == null) {
            body.setText("Putar lagu dulu untuk melihat liriknya.");
            return;
        }
        fetchLyrics(t, body);
    }

    void fetchLyrics(Track t, TextView body) {
        runIo(() -> {
            String lyric = null;
            try {
                String url = "https://lrclib.net/api/search?track_name=" + enc(t.title)
                        + "&artist_name=" + enc(t.artist);
                Resp r = http("GET", url, null, null, null, null);
                if (r.status >= 400) throw new ApiException(r.status, "server", "lrclib");
                JSONArray arr = new JSONArray(r.body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    String plain = o.optString("plainLyrics", "");
                    if (!TextUtils.isEmpty(plain)) {
                        lyric = plain;
                        break;
                    }
                }
            } catch (Exception e) {
                final String msg = describe(e);
                runUi(() -> body.setText("Lirik tidak bisa dimuat.\n" + msg
                        + "\n\nCoba lagi nanti — Chiroot tidak pernah menampilkan lirik karangan."));
                return;
            }
            final String result = lyric;
            runUi(() -> {
                if (result == null) {
                    body.setText("Lirik untuk lagu ini belum tersedia di sumber lirik publik.\n\n"
                            + "Chiroot tidak membuat atau mengarang lirik.");
                } else {
                    body.setText(result);
                }
            });
        });
    }

    /* =====================================================================
     * 22. PROFILE
     * ===================================================================== */

    void showProfileDialog() {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        ScrollView sv = new ResponsiveScrollView(this);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(20), dp(20), dp(20), dp(18));

        c.addView(text("Profile", 22f, C_INK, true));

        FrameLayout banner = new FrameLayout(this);
        banner.setBackground(bg(Color.parseColor(profileBanner), 18f, C_INK, 1.5f));
        banner.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));
        c.addView(banner);

        ImageView avatar = new ImageView(this);
        avatar.setBackground(bg(C_PAPER_2, 48f, C_INK, 2f));
        avatar.setClipToOutline(true);
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(dp(76), dp(76));
        alp.gravity = Gravity.BOTTOM | Gravity.START;
        alp.leftMargin = dp(12);
        alp.bottomMargin = -dp(24);
        banner.addView(avatar, alp);
        if (!TextUtils.isEmpty(userAvatar)) {
            final String u = userAvatar;
            runIo(() -> {
                Bitmap b = fetchBitmap(u);
                if (b != null) runUi(() -> avatar.setImageBitmap(b));
            });
        }

        LinearLayout body = column();
        body.setPadding(0, dp(30), 0, 0);
        c.addView(body);

        if (guestMode) {
            body.addView(text("Mode Tamu", 20f, C_INK, true));
            body.addView(text("Koleksi disimpan lokal di perangkat ini. Login Google untuk "
                    + "mengaktifkan crate cloud, laporan, dan profil.", 13f, C_MUTED, false));
            TextView login = primaryButton("Login dengan Google", v -> {
                d.dismiss();
                startGoogleOAuth();
            });
            LinearLayout.LayoutParams llp = matchWrap();
            llp.topMargin = dp(14);
            login.setLayoutParams(llp);
            body.addView(login);
        } else {
            EditText nameIn = styledInput("Display name", userName, InputType.TYPE_CLASS_TEXT);
            EditText bioIn = styledInput("Bio / description", profileBio, InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            EditText avatarIn = styledInput("URL foto profil (opsional)", userAvatar,
                    InputType.TYPE_TEXT_VARIATION_URI);
            c.addView(label("NAMA"));
            c.addView(nameIn);
            c.addView(label("DESKRIPSI"));
            c.addView(bioIn);
            c.addView(label("FOTO PROFIL"));
            c.addView(avatarIn);
            TextView email = text(userEmail, 12f, C_MUTED, false);
            email.setPadding(0, dp(10), 0, 0);
            c.addView(email);

            TextView save = primaryButton("Save changes", v -> {
                final String n = nameIn.getText().toString().trim();
                final String b = bioIn.getText().toString().trim();
                final String a = avatarIn.getText().toString().trim();
                saveProfile(n, b, a, d);
            });
            LinearLayout.LayoutParams slp = matchWrap();
            slp.topMargin = dp(14);
            save.setLayoutParams(slp);
            c.addView(save);
        }

        TextView logout = subtleButton(guestMode ? "Close" : "Logout", v -> {
            d.dismiss();
            if (guestMode) return;
            confirm("Logout?", "Kamu akan keluar dari akun ini di perangkat ini.",
                    "Logout", this::signOut);
        });
        LinearLayout.LayoutParams olp = matchWrap();
        olp.topMargin = dp(8);
        logout.setLayoutParams(olp);
        c.addView(logout);

        sv.addView(c);
        d.setContentView(sv);
        showDialog(d);
    }

    void saveProfile(String name, String bio, String avatar, @Nullable Dialog owner) {
        if (guestMode) {
            toast("Login dulu untuk menyimpan profil.");
            return;
        }
        if (!online()) {
            showConnectionError("Butuh koneksi internet untuk menyimpan profil.",
                    () -> saveProfile(name, bio, avatar, owner));
            return;
        }
        runIo(() -> {
            try {
                JSONObject patch = new JSONObject();
                patch.put("display_name", trim(name, 60));
                patch.put("description", trim(bio, 300));
                patch.put("pfp", avatar);
                patch.put("banner_color", profileBanner);
                patch.put("updated_at", nowIso());
                rest("PATCH", "profiles?id=eq." + enc(userId), patch);
                userName = name;
                profileBio = bio;
                userAvatar = avatar;
                saveSession();
                runUi(() -> {
                    if (owner != null && owner.isShowing()) owner.dismiss();
                    refreshAppBar();
                    toast("Profil tersimpan.");
                });
            } catch (Exception e) {
                runUi(() -> showOperationError(e, "Profil belum tersimpan", () -> saveProfile(name, bio, avatar, owner)));
            }
        });
    }

    /* =====================================================================
     * 23. SETTINGS — account, audio, DSP, backup, maintenance, credits
     * ===================================================================== */

    void showSettingsDialog() {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        ScrollView sv = new ResponsiveScrollView(this);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.addView(text("Settings", 22f, C_INK, true));

        // ---- account ----
        c.addView(sectionTitle("ACCOUNT"));
        c.addView(text(guestMode ? "Mode tamu (lokal)" : userEmail, 14f, C_INK_SOFT, false));
        c.addView(buttonRow(
                outlineButton(guestMode ? "Login Google" : "Edit profile", v -> {
                    d.dismiss();
                    showProfileDialog();
                }),
                outlineButton("Logout", v -> {
                    d.dismiss();
                    confirm("Logout?", "Kamu akan keluar dari akun ini.", "Logout", this::signOut);
                })));

        // ---- audio ----
        c.addView(sectionTitle("AUDIO"));
        c.addView(toggleRow("Mutar vinyl", showVinylSpin, checked -> {
            showVinylSpin = checked;
            saveSettings();
            if (vinyl != null) vinyl.setPlaying(controller != null && controller.isPlaying());
        }));
        c.addView(toggleRow("Playback audio processing (EQ gramofon)", dspEnabled, checked -> {
            dspEnabled = checked;
            saveSettings();
            sendDspCommand(checked);
            updateDspStatusLater();
        }));
        String status = prefs.getString("dsp_status", "Menunggu sesi audio (putar lagu dulu)");
        TextView dspStatus = text(status, 11f, C_MUTED, false);
        dspStatus.setPadding(0, dp(4), 0, dp(2));
        c.addView(dspStatus);
        c.addView(text("Diproses di jalur audio playback (platform Equalizer pada sesi audio ExoPlayer). "
                + "Bukan mikrofon. Noise cancellation lingkungan sejati butuh hardware headphone — "
                + "Chiroot tidak akan menampilkan status palsu.", 10f, C_MUTED, false));

        c.addView(buttonRow(
                outlineButton("Audio output", v -> showAudioOutputDialog()),
                outlineButton("Volume", v -> showVolumeDialog())));

        // ---- data ----
        c.addView(sectionTitle("BACKUP & DATA"));
        c.addView(buttonRow(
                outlineButton("Backup ZIP", v -> exportBackup()),
                outlineButton("Restore ZIP", v -> importBackup())));

        c.addView(buttonRow(
                outlineButton("Reset Crate", v -> confirm("Reset Crate?",
                        "Semua lagu dihapus dari crate " + (guestMode ? "lokal" : "cloud") + ". Tindakan ini tidak bisa dibatalkan.",
                        "Reset", this::resetCrate)),
                outlineButton("Patch notes", v -> showPatchNotes())));

        // ---- about ----
        c.addView(sectionTitle("ABOUT"));
        c.addView(text("Chiroot Android " + BuildConfig.VERSION_NAME + " · native Java", 13f, C_INK_SOFT, false));
        c.addView(text("Terinspirasi chiroot.my.id — pemutar musik gramofon klasik.", 11f, C_MUTED, false));
        c.addView(buttonRow(
                outlineButton("Credits", v -> showCredits()),
                outlineButton("Sumber lagu", v -> info("Sumber musik",
                        "Chiroot tidak menyediakan katalog musik. Semua lagu berasal dari file yang kamu "
                                + "unggah sendiri ke crate-mu (Supabase Storage) atau file lokal di perangkat.",
                        "Mengerti", null))));

        TextView close = primaryButton("Close", v -> d.dismiss());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(16);
        close.setLayoutParams(clp);
        c.addView(close);

        sv.addView(c);
        d.setContentView(sv);
        showDialog(d);
    }

    TextView sectionTitle(String s) {
        TextView t = text(s, 12f, C_WOOD, true);
        t.setLetterSpacing(0.1f);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    LinearLayout buttonRow(View a, View b) {
        LinearLayout r = row();
        r.setPadding(0, dp(6), 0, 0);
        a.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(a);
        r.addView(spacerH(8));
        r.addView(b);
        return r;
    }

    interface OnToggle {
        void onChanged(boolean checked);
    }

    View toggleRow(String labelText, boolean checked, OnToggle cb) {
        LinearLayout r = row();
        r.setPadding(0, dp(8), 0, dp(8));
        r.addView(text(labelText, 14f, C_INK_SOFT, false),
                lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pill = text(checked ? "ON" : "OFF", 13f, Color.WHITE, true);
        pill.setGravity(Gravity.CENTER);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setBackground(bg(checked ? C_TEAL : 0xFFBFB5A4, 14f, 0, 0));
        pill.setPadding(dp(16), dp(8), dp(16), dp(8));
        final boolean[] state = {checked};
        pill.setOnClickListener(v -> {
            state[0] = !state[0];
            pill.setText(state[0] ? "ON" : "OFF");
            pill.setBackground(bg(state[0] ? C_TEAL : 0xFFBFB5A4, 14f, 0, 0));
            cb.onChanged(state[0]);
        });
        r.addView(pill);
        return r;
    }

    /**
     * The flag is written to shared preferences; the playback service holds a change
     * listener and applies the equalizer to the live ExoPlayer audio session.
     */
    void sendDspCommand(boolean enabled) {
        // saveSettings() has already persisted the JSON setting observed by the service.
        // Never claim success before the service reports the actual effect state.
        prefs.edit().remove("dsp_status").apply();
    }

    void updateDspStatusLater() {
        ui.postDelayed(() -> {
            toast(prefs.getString("dsp_status", "Menunggu sesi audio; putar lagu untuk memeriksa dukungan"));
        }, 700);
    }

    void showVolumeDialog() {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(20), dp(20), dp(20), dp(18));
        c.addView(text("Volume", 20f, C_INK, true));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(Math.round(prefs.getFloat("volume", 1f) * 100));
        TextView val = text(sb.getProgress() + "%", 14f, C_MUTED, false);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                val.setText(p + "%");
                if (fromUser) setVolumePercent(p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        c.addView(sb);
        c.addView(val);
        c.addView(subtleButton("Close", v -> d.dismiss()), matchWrap());
        d.setContentView(c);
        showDialog(d);
    }

    void showAudioOutputDialog() {
        Dialog d = new Dialog(this, R.style.ChirootDialog);
        LinearLayout c = column();
        c.setBackground(bg(C_PAPER, 24f, 0xFFE3D8C4, 1f));
        c.setPadding(dp(20), dp(20), dp(20), dp(18));
        c.addView(text("Audio output", 20f, C_INK, true));
        c.addView(text("Perangkat audio yang terlihat oleh Android:", 12f, C_MUTED, false));

        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int count = 0;
        if (am != null) {
            for (AudioDeviceInfo info : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                String name = deviceTypeName(info.getType());
                LinearLayout r = row();
                r.setPadding(0, dp(8), 0, dp(8));
                r.addView(text(name, 14f, C_INK_SOFT, false), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r.addView(text("tersedia", 11f, C_MUTED, false));
                r.setOnClickListener(v -> info("Ganti output",
                        "Android mengatur routing audio di level sistem. Pakai pengalih output media "
                                + "(notifikasi media / pengaturan Bluetooth) untuk berpindah ke "
                                + name + ". Chiroot tidak memaksa routing perangkat.",
                        "Mengerti", null));
                c.addView(r);
                count++;
            }
        }
        if (count == 0) c.addView(text("Tidak ada perangkat output yang terdeteksi.", 13f, C_MUTED, false));
        c.addView(text("Pencabutan headphone/Bluetooth otomatis menjeda musik.", 10f, C_MUTED, false));
        TextView close = primaryButton("Close", v -> d.dismiss());
        LinearLayout.LayoutParams clp = matchWrap();
        clp.topMargin = dp(12);
        close.setLayoutParams(clp);
        c.addView(close);
        d.setContentView(c);
        showDialog(d);
    }

    String deviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Speaker perangkat";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "Earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Headphone kabel";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Headset kabel";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth audio";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth headset";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB headset";
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return "Hearing aid";
            default:
                return "Perangkat audio (tipe " + type + ")";
        }
    }

    /* ---- patch notes & credits: static, honest ---- */

    void showPatchNotes() {
        String notes = "Chiroot Android 1.0.0\n"
                + "• Migrasi native penuh (Java, tanpa WebView)\n"
                + "• Gramophone player dengan vinyl animasi nyata\n"
                + "• Background playback via MediaSessionService + notifikasi media\n"
                + "• Audio focus, ducking, dan jeda otomatis saat headphone dicabut\n"
                + "• The Crate cloud (Supabase Storage + Postgres)\n"
                + "• Add Music dengan quick auto-fill dari nama file\n"
                + "• Poster generator (Canvas + ekspor SVG)\n"
                + "• Listening report dari riwayat putar asli\n"
                + "• Lirik dari sumber publik (tanpa lirik karangan)\n"
                + "• Profil, backup/restore ZIP, playback audio processing\n"
                + "• Modal error koneksi/server dengan retry yang benar-benar mengulang";
        info("Patch Notes", notes, "Mantap", null);
    }

    void showCredits() {
        info("Credits", "Chiroot Android — dibangun ulang secara native dengan Java, AndroidX, "
                + "dan Android Media3/ExoPlayer.\n\n"
                + "Desain visual mengikuti identitas Chiroot (gramofon retro, kertas sketch, vinyl).\n"
                + "Backend: Supabase (Auth, Postgres, Storage).\n"
                + "Lirik: LRCLIB.\n"
                + "Ikon aplikasi & ilustrasi error: aset Chiroot yang kamu sediakan.", "Close", null);
    }

    /* =====================================================================
     * 24. BACKUP / RESTORE (ZIP) — never includes auth tokens
     * ===================================================================== */

    void exportBackup() {
        final String payload;
        try {
            JSONObject root = new JSONObject();
            JSONArray tracks = new JSONArray();
            for (Track t : crate) tracks.put(t.toJson());
            JSONArray hist = new JSONArray();
            for (Hist h : history) hist.put(h.toJson());
            JSONObject settings = new JSONObject();
            settings.put("dsp", dspEnabled);
            settings.put("spin", showVinylSpin);
            JSONObject profile = new JSONObject();
            profile.put("display_name", userName);
            profile.put("bio", profileBio);
            profile.put("avatar_url", userAvatar);
            root.put("app", "Chiroot Android");
            root.put("version", BuildConfig.VERSION_NAME);
            root.put("exported_at", nowIso());
            root.put("tracks", tracks);
            root.put("history", hist);
            root.put("settings", settings);
            root.put("profile", profile);
            payload = root.toString(2);
        } catch (JSONException e) {
            toast("Gagal membuat backup.");
            return;
        }
        final byte[] zip = zipBytes(payload);
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, "chiroot-backup-" + new SimpleDateFormat("yyyyMMdd",
                Locale.US).format(new Date()) + ".zip");
        pendingBackupForSave = "";
        try {
            startActivityForResult(i, REQ_SAVE_BACKUP);
            // Write the zip once the user picked a destination.
            pendingBackupBytes = zip;
        } catch (ActivityNotFoundException e) {
            toast("Tidak ada penyimpan file.");
        }
    }

    byte[] pendingBackupBytes;

    static byte[] zipBytes(String json) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(bo)) {
                zo.putNextEntry(new java.util.zip.ZipEntry("chiroot-backup.json"));
                zo.write(json.getBytes(StandardCharsets.UTF_8));
                zo.closeEntry();
            }
            return bo.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    void importBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN_BACKUP);
        } catch (ActivityNotFoundException e) {
            toast("Tidak ada penyimpan file.");
        }
    }

    void restoreBackupFrom(Uri uri) {
        runIo(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Tidak bisa membuka file");
                java.util.zip.ZipInputStream zi = new java.util.zip.ZipInputStream(in);
                java.util.zip.ZipEntry e = zi.getNextEntry();
                if (e == null) throw new IOException("ZIP kosong");
                String json = new String(readStreamBytes(zi), StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(json);
                JSONArray tracks = root.optJSONArray("tracks");
                JSONArray hist = root.optJSONArray("history");
                JSONObject settings = root.optJSONObject("settings");
                JSONObject profile = root.optJSONObject("profile");

                List<Track> newTracks = new ArrayList<>();
                if (tracks != null) {
                    for (int k = 0; k < tracks.length(); k++) {
                        Track t = Track.fromJson(tracks.getJSONObject(k));
                        if (!TextUtils.isEmpty(t.title)) newTracks.add(t);
                    }
                }
                List<Hist> newHist = new ArrayList<>();
                if (hist != null) {
                    for (int k = 0; k < hist.length(); k++) newHist.add(Hist.fromJson(hist.getJSONObject(k)));
                }
                runUi(() -> confirm("Restore backup?",
                        newTracks.size() + " lagu dan " + newHist.size() + " entri riwayat akan digabung "
                                + "ke data di perangkat ini. Token login tidak pernah ikut di backup.",
                        "Restore", () -> {
                            crate.addAll(newTracks);
                            history.addAll(newHist);
                            history.sort((a, b) -> Long.compare(b.playedAt, a.playedAt));
                            saveCrateCache();
                            saveLocalHistory();
                            if (settings != null) {
                                dspEnabled = settings.optBoolean("dsp", dspEnabled);
                                showVinylSpin = settings.optBoolean("spin", showVinylSpin);
                                saveSettings();
                            }
                            if (profile != null && !guestMode) {
                                saveProfile(profile.optString("display_name", userName),
                                        profile.optString("bio", profileBio),
                                        profile.optString("avatar_url", userAvatar), null);
                            }
                            renderDashboard();
                            toast("Backup dipulihkan.");
                        }));
            } catch (Exception e) {
                runUi(() -> toast("Backup tidak valid: " + e.getMessage()));
            }
        });
    }

    void resetCrate() {
        if (guestMode) {
            crate.clear();
            history.clear();
            saveCrateCache();
            saveLocalHistory();
            syncQueue();
            renderDashboard();
            toast("Crate lokal dikosongkan.");
            return;
        }
        if (!online()) {
            showConnectionError("Butuh koneksi internet untuk mengosongkan crate cloud.", this::resetCrate);
            return;
        }
        runIo(() -> {
            try {
                rest("DELETE", "crate_tracks?user_id=eq." + enc(userId), null);
                runUi(() -> {
                    crate.clear();
                    history.clear();
                    saveCrateCache();
                    saveLocalHistory();
                    syncQueue();
                    renderDashboard();
                    toast("Crate cloud dikosongkan.");
                });
            } catch (Exception e) {
                runUi(() -> showOperationError(e, "Crate belum dikosongkan", this::resetCrate));
            }
        });
    }

    /* =====================================================================
     * 25. PERMISSIONS + LIFECYCLE GLUE
     * ===================================================================== */

    void maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    void restoreFromCloud() {
        if (!online()) {
            toast("Offline — menampilkan cache lokal.");
            loadCrateCache();
            renderDashboard();
            return;
        }
        loadCloudData(null);
    }

    /** Called by the SAF pickers' backup path. */
    @Override
    protected void onResume() {
        super.onResume();
        handleOAuthDeepLink(getIntent());
        refreshAppBar();
    }
}
