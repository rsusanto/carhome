package id.rsusanto.carhome;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The "now playing" card: title, artist, album art (which also tints the card) and
 * prev / play-pause / next. Needs notification access to see other apps' media sessions.
 */
final class NowPlaying {
    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Runs when the card is tapped and no player is known (e.g. open a pinned music app). */
    private final Runnable openFallback;

    private MediaSessionManager sessionManager;
    private MediaController controller;
    private boolean listenerRegistered;
    private final List<MediaController> sessions = new ArrayList<>();
    private final List<SessionWatch> watches = new ArrayList<>();

    private LinearLayout card;
    private ImageView art;
    private TextView title, artist;
    private ImageButton play;
    private int tint = -1;       // album-art colour on the card (0 = default theme)
    private String artKey;       // what the art/tint currently show, to skip redoing them
    private int playIcon;        // drawable the play button shows now (0 = not set yet)

    NowPlaying(Activity act, Runnable openFallback) {
        this.act = act;
        this.openFallback = openFallback;
    }

    View build() {
        card = new LinearLayout(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(act, 12);
        card.setPadding(p, p, p, p);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openApp(); }
        });

        art = new ImageView(act);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.rounded(act, Ui.ART_BG, 12));
        art.setClipToOutline(true);
        int size = Ui.dp(act, 60);
        card.addView(art, new LinearLayout.LayoutParams(size, size));

        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.leftMargin = Ui.dp(act, 12);
        tlp.rightMargin = Ui.dp(act, 8);
        card.addView(texts, tlp);

        title = Ui.text(act, "", 17, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);

        artist = Ui.text(act, "", 14, Ui.TEXT_DIM);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(artist);

        ImageButton prev = Ui.iconButton(act, R.drawable.ic_prev, 48, 12, "Previous");
        play = Ui.iconButton(act, R.drawable.ic_play, 56, 14, "Play");
        ImageButton next = Ui.iconButton(act, R.drawable.ic_next, 48, 12, "Next");
        prev.setBackground(Ui.circleButton(act));
        play.setBackground(Ui.circleButton(act));
        next.setBackground(Ui.circleButton(act));
        card.addView(prev);
        addGap(10);
        card.addView(play);
        addGap(10);
        card.addView(next);

        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (controller != null) controller.getTransportControls().skipToPrevious();
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (controller != null) controller.getTransportControls().skipToNext();
            }
        });
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (controller == null) { openApp(); return; }
                if (isPlaying(controller)) controller.getTransportControls().pause();
                else controller.getTransportControls().play();
            }
        });

        showIdle();
        return card;
    }

    private void addGap(int dp) {
        card.addView(new View(act), new LinearLayout.LayoutParams(Ui.dp(act, dp), 1));
    }

    // ---------------------------------------------------------------- lifecycle

    /** Call from onResume: the user may have just granted notification access. */
    void resume() {
        if (listenerRegistered) return;
        if (!hasAccess()) { showIdle(); return; }
        sessionManager = (MediaSessionManager) act.getSystemService(Context.MEDIA_SESSION_SERVICE);
        ComponentName cn = listenerComponent();
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, cn, ui);
            listenerRegistered = true;
            sessionsListener.onActiveSessionsChanged(sessionManager.getActiveSessions(cn));
        } catch (SecurityException ex) {
            showIdle();
        }
    }

    void destroy() {
        if (listenerRegistered) sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
        listenerRegistered = false;
        watch(null);
    }

    // ---------------------------------------------------------------- access

    private ComponentName listenerComponent() {
        return new ComponentName(act, MediaListener.class);
    }

    private boolean hasAccess() {
        String s = Settings.Secure.getString(act.getContentResolver(), "enabled_notification_listeners");
        if (s == null) return false;
        ComponentName mine = listenerComponent();
        for (String entry : s.split(":")) {
            if (mine.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    void openAccessSettings() {
        // Some head-unit firmwares hide this screen; fall back to app info.
        if (!Ui.tryStart(act, new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))) {
            Ui.openAppInfo(act, act.getPackageName());
        }
    }

    // ---------------------------------------------------------------- sessions

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) {
                    watch(list);
                    pick();
                }
            };

    /**
     * Registered on EVERY media session, not just the shown one. Head units often keep their own
     * player (radio, USB music, Bluetooth) as a paused session; without this, a song started later
     * in YouTube Music/Spotify wouldn't show until the session list itself changed.
     * One per session, so metadata (with its album art) from a session that isn't shown is ignored.
     */
    private final class SessionWatch extends MediaController.Callback {
        final MediaController session;

        SessionWatch(MediaController session) { this.session = session; }

        @Override public void onPlaybackStateChanged(PlaybackState state) { pick(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            if (isShown(session)) render(metadata);
            else if (controller == null) pick(); // nothing shown yet: this one may have info now
        }
        @Override public void onSessionDestroyed() { pick(); }
    }

    private void watch(List<MediaController> list) {
        for (SessionWatch w : watches) w.session.unregisterCallback(w);
        watches.clear();
        sessions.clear();
        if (list != null) sessions.addAll(list);
        for (MediaController c : sessions) {
            SessionWatch w = new SessionWatch(c);
            c.registerCallback(w, ui);
            watches.add(w);
        }
    }

    private boolean isShown(MediaController c) {
        return controller != null && c.getSessionToken().equals(controller.getSessionToken());
    }

    /**
     * Show the session that's playing; otherwise keep the current one; otherwise the most recent.
     * A paused session with no track info (like the head unit's Bluetooth player right after boot)
     * isn't worth showing: the card stays on "Nothing playing" until something plays.
     * If the shown session stays the same, only the play/pause icon needs updating.
     */
    private void pick() {
        MediaController pick = null, current = null;
        for (MediaController c : sessions) {
            if (pick == null && isPlaying(c)) pick = c;
            if (isShown(c)) current = c;
        }
        if (pick == null && current != null && hasInfo(current)) pick = current;
        if (pick == null) {
            for (MediaController c : sessions) if (hasInfo(c)) { pick = c; break; }
        }
        boolean same = pick != null && pick == current;
        controller = pick;
        if (same) updatePlayIcon(); else render();
    }

    private static boolean isPlaying(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING
                || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    /** True if the session says what it's playing (a title), so there's something to show. */
    private static boolean hasInfo(MediaController c) {
        MediaMetadata md = c.getMetadata();
        return md != null && (!TextUtils.isEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE))
                || !TextUtils.isEmpty(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)));
    }

    // ---------------------------------------------------------------- drawing

    private void updatePlayIcon() {
        boolean playing = controller != null && isPlaying(controller);
        int icon = playing ? R.drawable.ic_pause : R.drawable.ic_play;
        if (icon == playIcon) return; // state updates are frequent; don't re-inflate the same icon
        playIcon = icon;
        play.setImageResource(icon);
        play.setContentDescription(playing ? "Pause" : "Play");
    }

    private void showIdle() {
        if (title == null) return;
        showArt(null, null);
        updatePlayIcon();
        if (!hasAccess()) {
            title.setText("Now playing is off");
            artist.setText("Tap ⚙ → Enable now-playing controls");
        } else {
            title.setText("Nothing playing");
            artist.setText("Tap to open your music app");
        }
    }

    private void render() {
        render(controller == null ? null : controller.getMetadata());
    }

    private void render(MediaMetadata md) {
        if (controller == null) { showIdle(); return; }
        String t = null, a = null;
        Bitmap cover = null;
        if (md != null) {
            t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            cover = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (cover == null) cover = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (cover == null) cover = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        }
        title.setText(t != null ? t : appLabel(controller.getPackageName()));
        artist.setText(a != null ? a : "");
        // Every getMetadata() is a fresh copy, so compare by what the art belongs to, not by instance.
        showArt(cover, cover == null ? null
                : controller.getPackageName() + "\n" + t + "\n" + a + "\n" + cover.getWidth() + "x" + cover.getHeight());
        updatePlayIcon();
    }

    /** Album art (and the card tint taken from it), or the music-note placeholder when null. */
    private void showArt(Bitmap cover, String key) {
        if (cover == null) key = ""; // the placeholder
        if (key.equals(artKey)) return;
        artKey = key;
        if (cover != null) {
            cover = fitArt(cover);
            art.setPadding(0, 0, 0, 0);
            art.setImageBitmap(cover);
        } else {
            art.setImageResource(R.drawable.ic_music);
            int p = Ui.dp(act, 18);
            art.setPadding(p, p, p, p);
        }
        applyTint(cover != null ? Ui.dominantColor(cover) : 0);
    }

    /**
     * Covers often arrive at 500–1000+ px (1–4 MB each) for a 60 dp slot. Keep a copy just big
     * enough for it (the slot crops to fill, so the short side matches); the full one can be freed.
     */
    private Bitmap fitArt(Bitmap cover) {
        int target = Ui.dp(act, 60);
        int shortSide = Math.min(cover.getWidth(), cover.getHeight());
        if (shortSide <= target) return cover;
        float scale = (float) target / shortSide;
        return Bitmap.createScaledBitmap(cover, Math.max(1, Math.round(cover.getWidth() * scale)),
                Math.max(1, Math.round(cover.getHeight() * scale)), true);
    }

    /** Tints the card and play button with the album's colour; 0 restores the default. */
    private void applyTint(int color) {
        if (color == tint) return;
        tint = color;
        if (color == 0) {
            card.setBackground(Ui.panel(act, Ui.CARD, 18));
            play.setBackground(Ui.circleButton(act));
            return;
        }
        GradientDrawable g = Ui.panel(act, Ui.mix(Ui.CARD, color, 0.32f), Ui.CARD, 18);
        g.setOrientation(GradientDrawable.Orientation.LEFT_RIGHT);
        card.setBackground(g);
        int rgb = color & 0x00FFFFFF;
        play.setBackground(Ui.circleButton(act, rgb | 0x66000000, rgb | 0xAA000000));
    }

    private String appLabel(String pkg) {
        PackageManager pm = act.getPackageManager();
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    // ---------------------------------------------------------------- diagnostics

    /**
     * ⚙ → Media sources: what this head unit's players actually report, to see why one (e.g. the
     * stock radio) doesn't show up. Lists media sessions, whether any audio is playing, and the
     * current notifications (some players only announce themselves there). Shown on screen only.
     */
    void showSources() {
        StringBuilder b = new StringBuilder();
        if (!hasAccess()) {
            b.append("Now-playing access is off, so Android won't list players.\n"
                    + "Turn it on: ⚙ → Enable now-playing controls.");
        } else {
            List<MediaController> list = sessions;
            if (!listenerRegistered) {
                try {
                    MediaSessionManager m = (MediaSessionManager) act.getSystemService(Context.MEDIA_SESSION_SERVICE);
                    list = m.getActiveSessions(listenerComponent());
                } catch (SecurityException ex) {
                    list = new ArrayList<>();
                }
            }
            AudioManager am = (AudioManager) act.getSystemService(Context.AUDIO_SERVICE);
            b.append("Audio playing now: ").append(am.isMusicActive() ? "yes" : "no").append("\n\n");
            b.append("Media sessions (").append(list.size()).append(")\n");
            if (list.isEmpty()) b.append("  none\n");
            for (MediaController c : list) {
                b.append("• ").append(appLabel(c.getPackageName())).append(" (").append(c.getPackageName()).append(")");
                if (isShown(c)) b.append("  ← shown");
                PlaybackState ps = c.getPlaybackState();
                b.append("\n   state: ").append(ps == null ? "none reported" : stateName(ps.getState()));
                MediaMetadata md = c.getMetadata();
                if (md == null) {
                    b.append("\n   no track info");
                } else {
                    b.append("\n   title: ").append(orDash(md.getString(MediaMetadata.METADATA_KEY_TITLE)))
                            .append("\n   artist: ").append(orDash(md.getString(MediaMetadata.METADATA_KEY_ARTIST)));
                }
                b.append("\n");
            }
            StatusBarNotification[] ns = MediaListener.active();
            b.append("\nNotifications");
            if (ns == null) {
                b.append(": can't read right now\n");
            } else {
                b.append(" (").append(ns.length).append(")\n");
                for (StatusBarNotification sbn : ns) {
                    Bundle x = sbn.getNotification().extras;
                    b.append("• ").append(appLabel(sbn.getPackageName())).append(" (").append(sbn.getPackageName()).append(")");
                    if (sbn.isOngoing()) b.append(" · ongoing");
                    if (x != null) {
                        b.append("\n   ").append(orDash(x.getCharSequence(Notification.EXTRA_TITLE)))
                                .append(" — ").append(orDash(x.getCharSequence(Notification.EXTRA_TEXT)));
                    }
                    b.append("\n");
                }
            }
        }
        Ui.dialog(act)
                .setTitle("Media sources")
                .setMessage(b.toString())
                .setPositiveButton("Close", null)
                .setNeutralButton("Refresh", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { showSources(); }
                })
                .show();
    }

    private static String orDash(CharSequence s) {
        return TextUtils.isEmpty(s) ? "—" : s.toString();
    }

    private static String stateName(int s) {
        switch (s) {
            case PlaybackState.STATE_NONE: return "none";
            case PlaybackState.STATE_STOPPED: return "stopped";
            case PlaybackState.STATE_PAUSED: return "paused";
            case PlaybackState.STATE_PLAYING: return "playing";
            case PlaybackState.STATE_FAST_FORWARDING: return "fast-forwarding";
            case PlaybackState.STATE_REWINDING: return "rewinding";
            case PlaybackState.STATE_BUFFERING: return "buffering";
            case PlaybackState.STATE_ERROR: return "error";
            case PlaybackState.STATE_CONNECTING: return "connecting";
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS: return "skipping back";
            case PlaybackState.STATE_SKIPPING_TO_NEXT: return "skipping ahead";
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM: return "skipping";
            default: return "unknown (" + s + ")";
        }
    }

    // ---------------------------------------------------------------- tap

    private void openApp() {
        if (!hasAccess()) { openAccessSettings(); return; }
        if (controller != null) {
            PendingIntent pi = controller.getSessionActivity();
            if (pi != null) {
                try { pi.send(); return; } catch (PendingIntent.CanceledException ignored) {}
            }
            Intent li = act.getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
            if (li != null && Ui.tryStart(act, li)) return;
        }
        openFallback.run();
    }
}
