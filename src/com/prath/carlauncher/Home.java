package com.prath.carlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import com.prath.carlauncher.Draw.Glyph;
import com.prath.carlauncher.Draw.Pal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// ponytail: one Activity, views built in code, no libraries, no image assets -> tiny RAM footprint.
// Layout (top→bottom): [speed | now playing], [volume | clock | brightness · theme], one row of app tiles.
// A theme change rebuilds the views under a fading snapshot of the old ones (no Activity restart).
public class Home extends Activity implements LocationListener {
    static final int SLOTS = 8; // app tiles in the dock; a 9th tile is "Apps"
    // Per tile: candidate apps, first installed wins (checked live, so an app installed later
    // just appears). A tile the user cleared or set by hand ignores these.
    static final String[][] DEFAULTS = {
            {"com.shihab.diplay"},                       // CarPlay
            // Android Auto: Open Headunit (bundled), its rebrands, Headunit Reloaded, or the unit's ZLink
            {"com.andrerinas.headunitrevived", "com.asjohn720.androidauto", "gb.xxy.hr", "com.zjinnova.zlink"},
            {"com.google.android.apps.maps"},
            {"com.spotify.music", "com.google.android.apps.youtube.music"},
            {}, {"com.google.android.youtube"}, {}, {"com.android.settings"}};
    static final int AA_SLOT = 1;
    static final String AA_STORE_ID = "gb.xxy.hr"; // Play Store page shown until an AA app is installed
    static final String[] MODES = {"auto", "light", "dark"};
    // ponytail: "headlights on" = unit dimmed the screen below this (0-255). Calibrate in the car,
    // or replace lightsOn() with the real headlight signal once tools/lights-probe.ps1 finds it.
    static final int DIM_BELOW = 100;
    static final String[] DIRS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    SharedPreferences prefs;
    PackageManager pm;
    float dp;
    final Handler ui = new Handler(Looper.getMainLooper());

    // theme
    Pal P;
    String themeMode;
    final LightsObserver lightsObserver = new LightsObserver();

    // volume + brightness sliders
    android.media.AudioManager audio;
    final VolumeReceiver volumeReceiver = new VolumeReceiver();
    Draw.Meter volMeter, briMeter;
    int ownBrightness = -1; // last value our slider wrote; Auto ignores it as a "headlights" signal

    // views (rebuilt on theme change)
    FrameLayout frame;
    final LinearLayout[] tiles = new LinearLayout[SLOTS];
    GridView drawer;
    View drawerPanel; // header + grid; this is what shows/hides

    // now playing
    MediaSessionManager msm;
    ComponentName listenerCn;
    MediaController ctl;
    final MediaCb mediaCb = new MediaCb();
    final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb = this::chooseSession;
    boolean sessionsHooked;
    ImageView artIv, playIv;
    TextView titleTv, artistTv, appTv, posTv, durTv;
    Draw.Bar progress;
    View progressRow;
    final Runnable progressTick = new Runnable() {
        public void run() {
            ui.removeCallbacks(this);
            if (updateProgress()) ui.postDelayed(this, 1000); // only ticks while something plays
        }
    };

    // GPS speed
    Draw.Gauge gauge;
    TextView speedTv, infoTv;
    LocationManager lm;
    Location last;
    long lastFixAt;
    boolean hasFix;
    float kmh, tripM, savedTripM;
    String heading = "", gpsNote = "Waiting for GPS";
    final Runnable staleCheck = new Runnable() {
        public void run() {
            if (hasFix && SystemClock.elapsedRealtime() - lastFixAt > 5000) {
                hasFix = false;
                gpsNote = "Waiting for GPS";
                renderSpeed(true);
            }
            ui.postDelayed(this, 3000);
        }
    };

    static class App {
        final String pkg;
        final String label;
        final ResolveInfo ri;
        App(String pkg, String label, ResolveInfo ri) { this.pkg = pkg; this.label = label; this.ri = ri; }
    }

    // ======================================================================= lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        pm = getPackageManager();
        prefs = getSharedPreferences("slots", MODE_PRIVATE);
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        listenerCn = new ComponentName(this, Listener.class);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        tripM = savedTripM = prefs.getFloat("tripM", 0);
        audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        registerReceiver(volumeReceiver, new android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION"));

        themeMode = prefs.getString("theme", "auto");
        P = wantDark() ? Draw.NIGHT : Draw.DAY;
        frame = new FrameLayout(this);
        setContentView(frame);
        buildUi();

        getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, lightsObserver);
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            startGps();
        else // background location so the trip keeps counting while DiPlay is on screen (Android 10+)
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    "android.permission.ACCESS_BACKGROUND_LOCATION"}, 7);
        ui.postDelayed(staleCheck, 3000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (int i = 0; i < SLOTS; i++) bind(i); // picks up installs/uninstalls
        hookSessions(); // retried here so it starts working right after access is granted
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(lightsObserver);
        unregisterReceiver(volumeReceiver);
        if (sessionsHooked) msm.removeOnActiveSessionsChangedListener(sessionsCb);
        if (ctl != null) ctl.unregisterCallback(mediaCb);
        lm.removeUpdates(this);
        ui.removeCallbacksAndMessages(null);
        saveTrip();
    }

    @Override
    protected void onNewIntent(Intent intent) { // Home pressed while we're open
        super.onNewIntent(intent);
        hideDrawer();
    }

    @Override
    public void onBackPressed() {
        hideDrawer(); // never exit the home screen
    }

    // ======================================================================= layout

    void buildUi() {
        frame.removeAllViews();
        getWindow().setStatusBarColor(P.bg1);
        getWindow().getDecorView().setSystemUiVisibility(P.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout home = new LinearLayout(this);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setBackground(backdrop());
        home.setPadding(px(8), px(6), px(8), px(4));

        LinearLayout top = new LinearLayout(this);
        top.addView(speedCard(), new LinearLayout.LayoutParams(px(290), -1));
        top.addView(mediaCard(), new LinearLayout.LayoutParams(0, -1, 1f));
        home.addView(top, new LinearLayout.LayoutParams(-1, 0, 1f));
        home.addView(clockRow(), new LinearLayout.LayoutParams(-1, -2));
        home.addView(dock(), new LinearLayout.LayoutParams(-1, px(112)));
        frame.addView(home);

        // All-apps screen: [back · "All apps"] header over the grid
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(backdrop());
        panel.setPadding(px(8), px(4), px(8), 0);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout back = new LinearLayout(this);
        back.setGravity(Gravity.CENTER);
        back.setBackground(soft(30, P.card));
        ImageView backIcon = new ImageView(this);
        backIcon.setImageDrawable(new Glyph(Glyph.BACK, P.text));
        back.addView(backIcon, new LinearLayout.LayoutParams(px(26), px(26)));
        back.setOnClickListener(v -> hideDrawer());
        header.addView(back, new LinearLayout.LayoutParams(px(82), px(82)));
        TextView title = text("All apps", 24, P.text, LIGHT);
        title.setPadding(px(8), 0, 0, 0);
        header.addView(title);
        panel.addView(header, new LinearLayout.LayoutParams(-1, -2));

        drawer = new GridView(this);
        drawer.setNumColumns(6);
        drawer.setSelector(new android.graphics.drawable.ColorDrawable(0));
        drawer.setOnItemClickListener((parent, v, p, id) -> launch(((App) parent.getItemAtPosition(p)).pkg));
        panel.addView(drawer, new LinearLayout.LayoutParams(-1, 0, 1f));
        panel.setVisibility(View.GONE);
        drawerPanel = panel;
        frame.addView(panel);

        for (int i = 0; i < SLOTS; i++) bind(i);
        updateMedia();
        renderSpeed(false);
        refreshMeters();
    }

    GradientDrawable backdrop() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{P.bg1, P.bg2});
    }

    View speedCard() {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(soft(26, P.card));
        card.setPadding(px(26), px(26), px(26), px(20));
        gauge = new Draw.Gauge(this, dp, P);
        card.addView(gauge, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        speedTv = text("--", 64, P.text, LIGHT);
        mid.addView(speedTv);
        mid.addView(caption("km/h"));
        card.addView(mid, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        infoTv = text("", 12, P.dim, MEDIUM);
        infoTv.setLetterSpacing(0.08f);
        card.addView(infoTv, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        card.setOnLongClickListener(v -> {
            tripM = 0;
            saveTrip();
            renderSpeed(false);
            Toast.makeText(this, "Trip reset", Toast.LENGTH_SHORT).show();
            return true;
        });
        return card;
    }

    /** Big art on the left; caption, title, artist, progress and controls on the right. */
    View mediaCard() {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(soft(26, P.card));
        card.setPadding(px(30), px(30), px(34), px(28));

        artIv = new ImageView(this);
        artIv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable artBg = new GradientDrawable();
        artBg.setColor(P.well);
        artBg.setCornerRadius(px(22));
        artIv.setBackground(artBg);
        artIv.setClipToOutline(true);
        artIv.setElevation(px(6));
        artIv.setOnClickListener(v -> openPlayer());
        card.addView(artIv, new LinearLayout.LayoutParams(px(196), px(196)));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(px(30), 0, 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(caption("Now playing"));
        appTv = text("", 11, P.accent, MEDIUM);
        appTv.setLetterSpacing(0.2f);
        appTv.setAllCaps(true);
        appTv.setPadding(px(10), 0, 0, 0);
        head.addView(appTv);
        right.addView(head);

        titleTv = text("", 26, P.text, MEDIUM);
        artistTv = text("", 16, P.dim, null);
        for (TextView tv : new TextView[]{titleTv, artistTv}) {
            tv.setGravity(Gravity.START);
            tv.setSingleLine();
            tv.setEllipsize(TextUtils.TruncateAt.END);
        }
        titleTv.setPadding(0, px(14), 0, 0);
        artistTv.setPadding(0, px(6), 0, 0);
        titleTv.setOnClickListener(v -> openPlayer());
        right.addView(titleTv);
        right.addView(artistTv);

        // progress: elapsed · bar · total (hidden for radio / live streams without a duration)
        LinearLayout prog = new LinearLayout(this);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        posTv = text("0:00", 12, P.dim, MEDIUM);
        durTv = text("0:00", 12, P.dim, MEDIUM);
        progress = new Draw.Bar(this, dp, P);
        progress.onSeek = f -> {
            MediaMetadata m = ctl == null ? null : ctl.getMetadata();
            long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (dur > 0) ctl.getTransportControls().seekTo((long) (f * dur));
        };
        prog.addView(posTv, new LinearLayout.LayoutParams(px(44), -2));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, px(28), 1f);
        blp.setMargins(px(10), 0, px(10), 0);
        prog.addView(progress, blp);
        prog.addView(durTv, new LinearLayout.LayoutParams(px(44), -2));
        progressRow = prog;
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.setMargins(0, px(16), 0, 0);
        right.addView(prog, plp);

        LinearLayout ctrls = new LinearLayout(this);
        ctrls.setGravity(Gravity.CENTER);
        ctrls.addView(roundBtn(Glyph.PREV, P.text, false, () -> ctl.getTransportControls().skipToPrevious()),
                new LinearLayout.LayoutParams(px(58), px(58)));
        playIv = roundBtn(Glyph.PLAY, P.onAccent, true, () -> {
            PlaybackState s = ctl.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) ctl.getTransportControls().pause();
            else ctl.getTransportControls().play();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(68), px(68));
        lp.setMargins(px(36), 0, px(36), 0);
        ctrls.addView(playIv, lp);
        ctrls.addView(roundBtn(Glyph.NEXT, P.text, false, () -> ctl.getTransportControls().skipToNext()),
                new LinearLayout.LayoutParams(px(58), px(58)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.setMargins(0, px(10), 0, 0);
        right.addView(ctrls, clp);

        card.addView(right, new LinearLayout.LayoutParams(0, -2, 1f));
        return card;
    }

    /** Round day/night button: tap cycles Auto → Day → Night. Icon shows what's showing now. */
    View themeButton() {
        return roundButton(P.dark ? Glyph.MOON : Glyph.SUN,
                "auto".equals(themeMode) ? "AUTO" : P.dark ? "NIGHT" : "DAY", v -> {
                    themeMode = MODES[(Arrays.asList(MODES).indexOf(themeMode) + 1) % MODES.length];
                    prefs.edit().putString("theme", themeMode).apply();
                    switchTheme();
                });
    }

    /** Small round soft button: accent icon + tiny caption. */
    LinearLayout roundButton(int glyph, String caption, View.OnClickListener click) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setBackground(soft(32, P.card));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(new Glyph(glyph, P.accent));
        b.addView(icon, new LinearLayout.LayoutParams(px(22), px(22)));
        TextView t = text(caption, 9, P.dim, MEDIUM);
        t.setLetterSpacing(0.15f);
        t.setPadding(0, px(3), 0, 0);
        b.addView(t);
        b.setOnClickListener(click);
        return b;
    }

    // ======================================================================= reverse camera

    // Names the unit's own camera / AV-in screens tend to have (firmware differs per unit).
    static final java.util.regex.Pattern CAMERA_NAMES = java.util.regex.Pattern.compile(
            "(?i)revers|rear|back.?car|backview|parking|avin|av.?in|aux.?in|camera|cam360|360");
    // Dash-cam / recorder apps also match "camera" but can grab the whole screen (seen on the
    // YT9216CJ: its DVR app blocked all touch until a restart), so they're never offered.
    static final java.util.regex.Pattern NOT_CAMERA = java.util.regex.Pattern.compile(
            "(?i)dvr|dash.?cam|recorder|record");

    View cameraButton() {
        LinearLayout b = roundButton(Glyph.CAMERA, "CAMERA", v -> openCamera());
        b.setOnLongClickListener(v -> { pickCamera(cameraCandidates()); return true; });
        return b;
    }

    /** Tap: open the remembered camera screen; first time, find it (one match opens, several → list). */
    void openCamera() {
        String saved = prefs.getString("camera", null);
        if (saved != null && NOT_CAMERA.matcher(saved).find()) { // remembered from an older version
            prefs.edit().remove("camera").apply();
            saved = null;
        }
        if (saved != null && launchComponent(saved)) return;
        // never auto-open an unknown screen: always let the driver confirm the first time
        pickCamera(cameraCandidates());
    }

    /** {label, "pkg/activity"}: launcher apps whose name looks like a camera, then hidden exported screens. */
    List<String[]> cameraCandidates() {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (App a : apps()) {
            String cls = a.ri.activityInfo.name;
            String all = a.label + " " + a.pkg + " " + cls;
            if (CAMERA_NAMES.matcher(all).find() && !NOT_CAMERA.matcher(all).find() && seen.add(a.pkg + "/" + cls))
                out.add(new String[]{a.label, a.pkg + "/" + cls});
        }
        try { // ponytail: one-off scan of every app's screens; only runs when the user asks for the camera
            for (android.content.pm.PackageInfo p : pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
                if (p.activities == null || p.packageName.equals(getPackageName())) continue;
                for (android.content.pm.ActivityInfo ai : p.activities) {
                    String simple = ai.name.substring(ai.name.lastIndexOf('.') + 1);
                    if (!ai.exported || !CAMERA_NAMES.matcher(simple).find()) continue;
                    String app = String.valueOf(p.applicationInfo.loadLabel(pm));
                    if (NOT_CAMERA.matcher(app + " " + p.packageName + " " + ai.name).find()) continue;
                    if (!seen.add(p.packageName + "/" + ai.name)) continue;
                    out.add(new String[]{app + "  ·  " + simple, p.packageName + "/" + ai.name});
                }
            }
        } catch (RuntimeException tooMany) { /* package list too big for one call: launcher matches still work */ }
        return out;
    }

    void pickCamera(List<String[]> found) {
        String[] names = new String[found.size() + 1];
        for (int k = 0; k < found.size(); k++) names[k] = found.get(k)[0];
        names[found.size()] = "Other app…";
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(found.isEmpty() ? "No camera screen found — pick the app" : "Which one is the camera?")
                .setItems(names, (d, w) -> {
                    if (w < found.size()) {
                        prefs.edit().putString("camera", found.get(w)[1]).apply();
                        launchComponent(found.get(w)[1]);
                    } else {
                        pickAnyAppAsCamera();
                    }
                })
                .show();
    }

    void pickAnyAppAsCamera() {
        List<App> list = apps();
        String[] names = new String[list.size()];
        for (int k = 0; k < list.size(); k++) names[k] = list.get(k).label;
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Camera button opens…")
                .setItems(names, (d, w) -> {
                    App a = list.get(w);
                    String comp = a.pkg + "/" + a.ri.activityInfo.name;
                    prefs.edit().putString("camera", comp).apply();
                    launchComponent(comp);
                })
                .show();
    }

    boolean launchComponent(String flat) {
        ComponentName cn = ComponentName.unflattenFromString(flat);
        if (cn == null) return false;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) { // uninstalled, or the screen refuses outside launches
            Toast.makeText(this, "Couldn't open that screen — long-press Camera to pick another",
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /** [volume slider]  clock | date  [brightness slider] */
    View clockRow() {
        LinearLayout outer = new LinearLayout(this);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.setPadding(px(22), 0, px(22), 0);

        volMeter = new Draw.Meter(this, dp, P, Glyph.SPEAKER);
        volMeter.onChange = f -> {
            int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(f * max), 0);
        };
        briMeter = new Draw.Meter(this, dp, P, Glyph.SUN);
        briMeter.onChange = this::setBrightness;

        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, px(46), 1f);
        LinearLayout.LayoutParams camLp = new LinearLayout.LayoutParams(px(84), px(84));
        camLp.setMargins(-px(12), 0, px(8), 0); // the soft surface has its own inset
        outer.addView(cameraButton(), camLp);
        outer.addView(volMeter, mlp);
        View clock = clockGroup();
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMargins(px(30), 0, px(30), 0);
        outer.addView(clock, clp);
        outer.addView(briMeter, new LinearLayout.LayoutParams(0, px(46), 1f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(px(84), px(84));
        tlp.setMargins(px(8), 0, -px(12), 0); // the soft surface has its own inset
        outer.addView(themeButton(), tlp);
        return outer;
    }

    void refreshMeters() {
        int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        volMeter.setValue(max == 0 ? 0 : audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) / (float) max);
        briMeter.setValue(brightness() / 255f);
    }

    int brightness() {
        return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 255);
    }

    void setBrightness(float f) {
        if (!Settings.System.canWrite(this)) { // one-time "Modify system settings" permission
            Toast.makeText(this, "Allow Car Home to modify system settings", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
            return;
        }
        ownBrightness = Math.max(5, Math.round(f * 255)); // never fully black
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, ownBrightness);
    }

    class VolumeReceiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(android.content.Context c, Intent i) { if (volMeter != null) refreshMeters(); }
    }

    View clockGroup() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, px(2), 0, px(2));
        TextClock time = new TextClock(this);
        time.setFormat12Hour("h:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(58);
        time.setTypeface(LIGHT);
        time.setTextColor(P.text);
        row.addView(time);

        View bar = new View(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(P.accent);
        bg.setCornerRadius(px(2));
        bar.setBackground(bg);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(px(3), px(34));
        blp.setMargins(px(22), 0, px(18), 0);
        row.addView(bar, blp);

        LinearLayout dates = new LinearLayout(this);
        dates.setOrientation(LinearLayout.VERTICAL);
        TextClock day = new TextClock(this);
        day.setFormat12Hour("EEEE");
        day.setFormat24Hour("EEEE");
        day.setAllCaps(true);
        day.setLetterSpacing(0.2f);
        day.setTextSize(11);
        day.setTypeface(MEDIUM);
        day.setTextColor(P.dim);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("d MMMM");
        date.setFormat24Hour("d MMMM");
        date.setTextSize(20);
        date.setTypeface(LIGHT);
        date.setTextColor(P.text);
        dates.addView(day);
        dates.addView(date);
        row.addView(dates);
        return row;
    }

    View dock() {
        LinearLayout dock = new LinearLayout(this);
        for (int i = 0; i <= SLOTS; i++) {
            LinearLayout t = tile(40);
            dock.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
            if (i < SLOTS) tiles[i] = t;
            else {
                ((ImageView) t.getChildAt(0)).setImageDrawable(new Glyph(Glyph.GRID, P.text));
                ((TextView) t.getChildAt(1)).setText("Apps");
                t.setOnClickListener(v -> showDrawer());
            }
        }
        return dock;
    }

    // ----------------------------------------------------------------------- view helpers

    Draw.Soft soft(float radiusDp, int fill) { return new Draw.Soft(dp, radiusDp, fill, P); }

    TextView text(String s, int sp, int color, Typeface tf) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (tf != null) tv.setTypeface(tf);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    TextView caption(String s) {
        TextView tv = text(s.toUpperCase(Locale.US), 11, P.dim, MEDIUM);
        tv.setLetterSpacing(0.2f);
        return tv;
    }

    /** Soft tile: icon + small label. */
    LinearLayout tile(int iconDp) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(soft(22, P.card));
        // shadowLayer on shapes is GPU-drawn only from Android 9; older needs a software layer
        if (Build.VERSION.SDK_INT < 28) t.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        t.addView(new ImageView(this), new LinearLayout.LayoutParams(px(iconDp), px(iconDp)));
        TextView tv = text("", 12, P.dim, null);
        tv.setSingleLine();
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(px(14), px(6), px(14), 0);
        t.addView(tv);
        return t;
    }

    /** Circular media button; the filled one uses the accent colour. */
    ImageView roundBtn(int glyph, int color, boolean filled, Runnable action) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(new Glyph(glyph, color));
        b.setPadding(px(filled ? 18 : 15), px(filled ? 18 : 15), px(filled ? 18 : 15), px(filled ? 18 : 15));
        ColorStateList ripple = ColorStateList.valueOf((P.accent & 0x00FFFFFF) | 0x55000000);
        if (filled) {
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(P.accent);
            b.setBackground(new RippleDrawable(ripple, dot, null));
            b.setElevation(px(4));
        } else {
            b.setBackground(new RippleDrawable(ripple, null, new ShapeDrawable(new OvalShape())));
        }
        b.setOnClickListener(v -> { if (ctl != null) action.run(); });
        return b;
    }

    int px(int dps) { return Math.round(dps * dp); }

    // ======================================================================= day / night

    boolean lightsOn() {
        int b = brightness();
        if (b == ownBrightness) return P != null && P.dark; // user moved our slider: not a headlight change
        return b < DIM_BELOW;
    }

    boolean wantDark() {
        return "dark".equals(themeMode) || ("auto".equals(themeMode) && lightsOn());
    }

    /** Rebuild in the new palette under a snapshot of the old screen, then fade the snapshot out. */
    void switchTheme() {
        P = wantDark() ? Draw.NIGHT : Draw.DAY;
        if (frame.getWidth() == 0) { buildUi(); return; }
        Bitmap snap = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
        frame.draw(new Canvas(snap));
        buildUi();
        ImageView cover = new ImageView(this);
        cover.setImageBitmap(snap);
        frame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        cover.animate().alpha(0f).setDuration(650).withEndAction(() -> {
            frame.removeView(cover);
            snap.recycle();
        });
    }

    class LightsObserver extends ContentObserver {
        LightsObserver() { super(new Handler(Looper.getMainLooper())); }
        @Override public void onChange(boolean self) {
            refreshMeters();
            if ("auto".equals(themeMode) && wantDark() != P.dark) switchTheme();
        }
    }

    int dialogTheme() {
        return P.dark ? android.R.style.Theme_Material_Dialog : android.R.style.Theme_Material_Light_Dialog;
    }

    // ======================================================================= app tiles

    void bind(int i) {
        LinearLayout t = tiles[i];
        ImageView iv = (ImageView) t.getChildAt(0);
        TextView tv = (TextView) t.getChildAt(1);
        String pkg = prefs.getString("s" + i, null); // null = never touched; "" = cleared by user
        boolean untouched = pkg == null;
        if (untouched) pkg = firstInstalled(DEFAULTS[i]);
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            iv.setImageDrawable(pm.getApplicationIcon(ai));
            tv.setText(label(pkg, pm.getApplicationLabel(ai)));
        } catch (Exception e) { // empty slot or app uninstalled
            pkg = null;
            if (untouched && i == AA_SLOT) { // no Android Auto app yet: offer to get one
                iv.setImageDrawable(new Glyph(Glyph.PLUS, P.accent));
                tv.setText("Android Auto");
                t.setOnClickListener(v -> openStore(AA_STORE_ID));
                t.setOnLongClickListener(v -> { pick(i); return true; });
                return;
            }
            iv.setImageDrawable(new Glyph(Glyph.PLUS, P.dim));
            tv.setText("Add");
        }
        final String p = pkg;
        t.setOnClickListener(v -> { if (p == null) pick(i); else launch(p); });
        t.setOnLongClickListener(v -> { pick(i); return true; });
    }

    // ponytail: names shown in Car Home only; the APKs stay untouched so they keep their signatures/updates
    static final java.util.Map<String, String> RENAME = java.util.Collections.singletonMap(
            "com.andrerinas.headunitrevived", "Android Auto"); // Open Headunit

    static String label(String pkg, CharSequence real) {
        String r = RENAME.get(pkg);
        return r != null ? r : String.valueOf(real);
    }

    String firstInstalled(String[] candidates) {
        for (String c : candidates) if (pm.getLaunchIntentForPackage(c) != null) return c;
        return null;
    }

    void openStore(String id) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=" + id)));
        } catch (Exception noStore) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=" + id)));
            } catch (Exception noBrowser) {
                Toast.makeText(this, "Install an Android Auto app (e.g. Headunit Reloaded), then it appears here",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    void launch(String pkg) {
        Intent it = pm.getLaunchIntentForPackage(pkg);
        if (it == null) { Toast.makeText(this, "Can't open " + pkg, Toast.LENGTH_SHORT).show(); return; }
        hideDrawer();
        startActivity(it);
    }

    List<App> apps() {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.equals(getPackageName())) out.add(new App(pkg, label(pkg, ri.loadLabel(pm)), ri));
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    void pick(int slot) {
        List<App> list = apps();
        String[] names = new String[list.size() + 1];
        names[0] = "— Clear this tile —";
        for (int k = 0; k < list.size(); k++) names[k + 1] = list.get(k).label;
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Put which app here?")
                .setItems(names, (d, w) -> {
                    if (w == 0) prefs.edit().putString("s" + slot, "").apply(); // "" = cleared, skip defaults
                    else prefs.edit().putString("s" + slot, list.get(w - 1).pkg).apply();
                    bind(slot);
                })
                .show();
    }

    void hideDrawer() { drawerPanel.setVisibility(View.GONE); }

    void showDrawer() {
        drawer.setAdapter(new Drawer(apps()));
        drawerPanel.setVisibility(View.VISIBLE);
    }

    class Drawer extends BaseAdapter {
        final List<App> list;
        Drawer(List<App> list) { this.list = list; }
        public int getCount() { return list.size(); }
        public Object getItem(int p) { return list.get(p); }
        public long getItemId(int p) { return p; }
        public View getView(int p, View v, ViewGroup parent) {
            LinearLayout t = v != null ? (LinearLayout) v : tile(46);
            if (v == null) t.setLayoutParams(new GridView.LayoutParams(-1, px(124)));
            App a = list.get(p);
            ((ImageView) t.getChildAt(0)).setImageDrawable(a.ri.loadIcon(pm));
            ((TextView) t.getChildAt(1)).setText(a.label);
            t.setClickable(false); // GridView's item click handles it
            t.setDuplicateParentStateEnabled(true); // still show the pressed look
            return t;
        }
    }

    // ======================================================================= now playing

    void hookSessions() {
        if (sessionsHooked) return;
        try {
            msm.addOnActiveSessionsChangedListener(sessionsCb, listenerCn);
            sessionsHooked = true;
            chooseSession(msm.getActiveSessions(listenerCn));
        } catch (SecurityException e) { // notification access not granted yet
            updateMedia();
        }
    }

    /** Prefer whatever is playing right now; otherwise the most recent session. */
    void chooseSession(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            if (pick == null) pick = c;
        }
        if (ctl != null) ctl.unregisterCallback(mediaCb);
        ctl = pick;
        if (ctl != null) ctl.registerCallback(mediaCb);
        updateMedia();
    }

    class MediaCb extends MediaController.Callback {
        @Override public void onMetadataChanged(MediaMetadata m) { updateMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { updateMedia(); }
        @Override public void onSessionDestroyed() {
            try { chooseSession(msm.getActiveSessions(listenerCn)); } catch (SecurityException e) { chooseSession(null); }
        }
    }

    void updateMedia() {
        if (!sessionsHooked || ctl == null) {
            placeholderArt();
            titleTv.setText(sessionsHooked ? "Nothing playing" : "Show your music here");
            artistTv.setText(sessionsHooked ? "Start music, radio or DiPlay" : "Tap to allow notification access");
            appTv.setText("");
            playIv.setImageDrawable(new Glyph(Glyph.PLAY, P.onAccent));
            progressRow.setVisibility(View.INVISIBLE);
            return;
        }
        String app = ctl.getPackageName();
        try { app = label(app, pm.getApplicationLabel(pm.getApplicationInfo(app, 0))); } catch (Exception ignored) {}
        MediaMetadata m = ctl.getMetadata();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m == null ? null : first(m.getString(MediaMetadata.METADATA_KEY_ARTIST),
                m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        // ponytail: art shown at whatever size the player sends; it's already in our heap via the session.
        Bitmap art = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null && m != null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        titleTv.setText(title != null ? title : app);
        artistTv.setText(artist != null ? artist : "");
        appTv.setText("·  " + app);
        if (art != null) { artIv.setPadding(0, 0, 0, 0); artIv.setImageBitmap(art); }
        else placeholderArt();
        PlaybackState s = ctl.getPlaybackState();
        boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
        playIv.setImageDrawable(new Glyph(playing ? Glyph.PAUSE : Glyph.PLAY, P.onAccent));
        progressTick.run();
    }

    /** Refresh the progress row; returns true while it should keep ticking (i.e. playing). */
    boolean updateProgress() {
        if (ctl == null || progressRow == null) return false;
        MediaMetadata m = ctl.getMetadata();
        PlaybackState s = ctl.getPlaybackState();
        long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (dur <= 0 || s == null) { progressRow.setVisibility(View.INVISIBLE); return false; }
        boolean playing = s.getState() == PlaybackState.STATE_PLAYING;
        long pos = s.getPosition();
        if (playing) pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        pos = Math.max(0, Math.min(pos, dur));
        progressRow.setVisibility(View.VISIBLE);
        progress.setValue(pos / (float) dur);
        posTv.setText(mmss(pos));
        durTv.setText(mmss(dur));
        return playing;
    }

    static String mmss(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    void placeholderArt() {
        artIv.setPadding(px(60), px(60), px(60), px(60));
        artIv.setImageDrawable(new Glyph(Glyph.NOTE, P.dim));
    }

    static String first(String... xs) {
        for (String x : xs) if (x != null && !x.isEmpty()) return x;
        return null;
    }

    void openPlayer() {
        if (!sessionsHooked) {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            return;
        }
        if (ctl == null) return;
        PendingIntent pi = ctl.getSessionActivity();
        if (pi != null) try { pi.send(); return; } catch (PendingIntent.CanceledException ignored) {}
        launch(ctl.getPackageName());
    }

    // ======================================================================= GPS speed

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startGps();
        else { gpsNote = "Location permission needed"; renderSpeed(false); }
    }

    // ponytail: listens for the launcher's whole life (not just while visible) so the trip
    // keeps counting under DiPlay; if Android kills the launcher, trip resumes from last save.
    void startGps() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (SecurityException | IllegalArgumentException e) {
            gpsNote = "No GPS on this device";
            renderSpeed(false);
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        float v = 0;
        if (loc.hasSpeed()) v = loc.getSpeed() * 3.6f;
        else if (last != null && loc.getTime() > last.getTime()) // cheap GPS chips often omit speed
            v = last.distanceTo(loc) / ((loc.getTime() - last.getTime()) / 1000f) * 3.6f;

        boolean moving = v > 3; // below this, GPS drift looks like motion
        if (last != null && moving && (!loc.hasAccuracy() || loc.getAccuracy() < 30))
            tripM += last.distanceTo(loc);
        float bearing = loc.hasBearing() ? loc.getBearing() : (last != null && moving ? last.bearingTo(loc) : -1);
        if (moving && bearing >= 0) heading = DIRS[(int) (((bearing + 360) % 360 + 22.5f) / 45) % 8];

        last = loc;
        lastFixAt = SystemClock.elapsedRealtime();
        hasFix = true;
        kmh = v;
        renderSpeed(true);
        if (Math.abs(tripM - savedTripM) >= 100) saveTrip();
    }

    void renderSpeed(boolean animate) {
        speedTv.setText(hasFix ? String.valueOf(Math.round(kmh)) : "--");
        gauge.set(hasFix ? kmh : 0, animate);
        String trip = String.format(Locale.US, "%.1f KM", tripM / 1000);
        infoTv.setText(hasFix ? (heading.isEmpty() ? trip : heading + "  ·  " + trip)
                : gpsNote.toUpperCase(Locale.US) + "  ·  " + trip);
    }

    @Override public void onStatusChanged(String p, int s, Bundle b) {}
    @Override public void onProviderEnabled(String p) {}
    @Override public void onProviderDisabled(String p) {}

    void saveTrip() {
        savedTripM = tripM;
        prefs.edit().putFloat("tripM", tripM).apply();
    }
}
