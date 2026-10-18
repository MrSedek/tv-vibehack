package com.gathin.tvbridge;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches the media sessions of the players in res/values/config.xml and records where the user
 * stopped. Being an enabled notification listener is what lets an ordinary app see other apps'
 * sessions; the notifications themselves are ignored.
 *
 * Players drop their session as soon as you leave the player screen, so a snapshot is taken on
 * every pause, on every switch to another video and when the session goes away.
 */
public class MediaWatchService extends NotificationListenerService {

    private static final String TAG = "TvBridge";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, Tracked> tracked = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::onSessionsChanged;
    private MediaSessionManager sessions;
    private Set<String> watched;
    private Set<String> youtube;

    @Override
    public void onListenerConnected() {
        watched = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.watched_apps)));
        youtube = new HashSet<>(Arrays.asList(getResources().getStringArray(R.array.youtube_apps)));
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatchService.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, me, handler);
            onSessionsChanged(sessions.getActiveSessions(me));
            Log.i(TAG, "watching media sessions of " + watched);
        } catch (SecurityException e) {
            Log.w(TAG, "no access to media sessions; is the listener allowed?", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsListener);
        for (Tracked t : tracked.values()) t.end();
        tracked.clear();
    }

    private void onSessionsChanged(List<MediaController> controllers) {
        Set<MediaSession.Token> alive = new HashSet<>();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (!watched.contains(c.getPackageName())) continue;
                alive.add(c.getSessionToken());
                if (!tracked.containsKey(c.getSessionToken())) {
                    tracked.put(c.getSessionToken(), new Tracked(c));
                }
            }
        }
        tracked.entrySet().removeIf(e -> {
            if (alive.contains(e.getKey())) return false;
            e.getValue().end();
            return true;
        });
    }

    /** One player's session: keeps its latest metadata and state, snapshots them into the history. */
    private final class Tracked extends MediaController.Callback {
        private final MediaController controller;
        private MediaMetadata metadata;
        private PlaybackState state;

        Tracked(MediaController c) {
            controller = c;
            metadata = c.getMetadata();
            state = c.getPlaybackState();
            c.registerCallback(this, handler);
            logKeys();
        }

        @Override
        public void onMetadataChanged(MediaMetadata md) {
            // SmartTube clears its metadata on the way out; keep what identifies the video.
            if (md == null || (!identifies(md) && metadata != null && identifies(metadata))) return;
            // Autoplay or a new pick: file the previous video before switching.
            if (metadata != null && identifies(metadata) && !sameItem(metadata, md)) snapshot();
            metadata = md;
            logKeys();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            state = s;
            if (s != null && (s.getState() == PlaybackState.STATE_PAUSED || s.getState() == PlaybackState.STATE_STOPPED)) {
                snapshot();
            }
        }

        @Override
        public void onSessionDestroyed() {
            end();
            tracked.remove(controller.getSessionToken());
        }

        void end() {
            snapshot();
            controller.unregisterCallback(this);
        }

        private void snapshot() {
            if (state == null) return;
            WatchItem w = new WatchItem();
            w.pkg = controller.getPackageName();
            boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
            w.positionMs = History.positionAt(state.getPosition(), state.getLastPositionUpdateTime(),
                    state.getPlaybackSpeed(), playing, SystemClock.elapsedRealtime());
            w.updatedAt = System.currentTimeMillis();
            Bitmap art = null;
            if (metadata != null) {
                w.mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                w.title = firstText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
                w.subtitle = firstText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST);
                w.artUrl = firstText(metadata, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                        MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI);
                w.durationMs = Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
                art = firstBitmap(metadata);
            }
            if (w.positionMs < 0) return;
            boolean yt = youtube.contains(w.pkg) && w.mediaId != null && w.mediaId.matches("[A-Za-z0-9_-]{6,20}");
            if (yt && w.artUrl == null && art == null) w.artUrl = "https://i.ytimg.com/vi/" + w.mediaId + "/mqdefault.jpg";
            HistoryStore.get(MediaWatchService.this).record(w, art, yt);
        }

        private void logKeys() {
            if (metadata != null) {
                Log.i(TAG, controller.getPackageName() + " metadata keys " + new ArrayList<>(metadata.keySet()));
            }
        }
    }

    private static boolean identifies(MediaMetadata md) {
        return md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) != null || md.getString(MediaMetadata.METADATA_KEY_TITLE) != null;
    }

    private static boolean sameItem(MediaMetadata a, MediaMetadata b) {
        String ia = a.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), ib = b.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (ia != null || ib != null) return ia != null && ia.equals(ib);
        String ta = a.getString(MediaMetadata.METADATA_KEY_TITLE), tb = b.getString(MediaMetadata.METADATA_KEY_TITLE);
        return ta == null ? tb == null : ta.equals(tb);
    }

    private static String firstText(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private static Bitmap firstBitmap(MediaMetadata md) {
        for (String k : new String[]{MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_ALBUM_ART}) {
            Bitmap b = md.getBitmap(k);
            if (b != null) return b;
        }
        return null;
    }
}
