package com.overdrive.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.overdrive.app.R;
import com.overdrive.app.auth.AuthManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * App-process audio player for the "Play Audio" automation / key-mapping action.
 *
 * <p><b>Why this exists in the app process.</b> The daemon ({@code app_process},
 * UID 2000) cannot play a {@link MediaPlayer} — {@code prepare()} fails on this
 * firmware with {@code status=0x80000000} because the media extractor doesn't
 * service the headless process (see {@link com.overdrive.app.byd.AudioPlaybackController}).
 * A framework MediaPlayer prepares normally in the real app process, so the daemon
 * shells {@code am start-foreground-service} to reach this exported service and hands
 * it the sound to play.
 *
 * <p><b>File source.</b> A library sound arrives as {@code libName}; the app can't read
 * {@code /data/local/tmp/.overdrive/audio} directly (SELinux untrusted_app), so it
 * streams the bytes from the daemon's authenticated {@code /api/audio/library/raw}
 * endpoint (JWT cookie via {@link AuthManager}). An external-storage sound arrives as
 * {@code filePath} and is opened directly (the app can read shared storage).
 *
 * <p>Single-player, replace-on-play: a new play stops the previous one. One-shots
 * self-stop the service on completion; looping playback lives until an explicit stop
 * (service stop or the {@link #ACTION_STOP} broadcast). Uses async prepare with a real
 * Looper (the service main thread), so no blocking on the caller.
 */
public final class MediaPlaybackService extends Service {

    private static final String TAG = "MediaPlaybackService";
    private static final String CHANNEL_ID = "overdrive_media_playback";
    private static final int NOTIFICATION_ID = 9971;
    /** Daemon base — same loopback the app's DaemonHttpClient uses. */
    private static final String DAEMON_BASE = "http://127.0.0.1:8080";
    /** Broadcast that stops playback (shared with the video activity + daemon stop()). */
    public static final String ACTION_STOP = "com.overdrive.app.action.STOP_MEDIA";

    private MediaPlayer player;
    private AudioManager audioManager;
    private Object audioFocusRequest; // AudioFocusRequest (API26+) or null
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private boolean stopReceiverRegistered;
    // TextToSpeech engine, lazily initialised on the first speak request. Held for the
    // service lifetime and shut down in onDestroy. TTS init is async, so a speak request
    // that arrives before init completes is queued in pendingSpeak and flushed onInit.
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeak;
    private String pendingSpeakChannel;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Log.i(TAG, "stop broadcast received");
            stopSelf();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP));
        stopReceiverRegistered = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getStringExtra("action");
        if ("stop".equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if ("speak".equals(action)) {
            String text = intent.getStringExtra("text");
            String ch = orDefault(intent.getStringExtra("channel"), "voice");
            speak(text, ch);
            return START_NOT_STICKY;
        }
        String channel = orDefault(intent.getStringExtra("channel"), "media");
        boolean loop = intent.getBooleanExtra("loop", false);
        String libName = intent.getStringExtra("libName");
        String filePath = intent.getStringExtra("filePath");

        Uri uri;
        Map<String, String> headers = null;
        if (libName != null && !libName.isEmpty()) {
            // Stream from the daemon (app can't read the library dir). Authenticated.
            uri = Uri.parse(DAEMON_BASE + "/api/audio/library/raw?name=" + Uri.encode(libName));
            headers = authHeaders();
        } else if (filePath != null && !filePath.isEmpty()) {
            uri = Uri.fromFile(new java.io.File(filePath));
        } else {
            Log.w(TAG, "no libName/filePath — nothing to play");
            stopSelf();
            return START_NOT_STICKY;
        }
        startPlayback(uri, headers, channel, loop);
        // Not sticky: if the OS kills us mid-clip we don't silently resurrect a sound.
        return START_NOT_STICKY;
    }

    private void startPlayback(Uri uri, Map<String, String> headers, String channel, boolean loop) {
        releasePlayer();
        requestFocus(channel, loop);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            int stream = streamForChannel(channel);
            if (isOemExtendedStream(stream)) {
                // OEM-EXTENDED stream (navigation=14, voice=16): these are NOT public
                // AudioManager constants, and AudioAttributes.setLegacyStreamType(14) does
                // NOT route to them — MediaPlayer derives its output stream from the usage
                // (USAGE_UNKNOWN→STREAM_MUSIC), so nav audio still came out the media
                // amplifier (the reported bug). the reference implementation routes nav via the
                // DEPRECATED-but-working direct setter setAudioStreamType(14) BEFORE prepare,
                // which is the only mechanism that lands on the OEM nav channel. Use it here.
                mp.setAudioStreamType(stream);
            } else {
                // Public stream (media/phone/alarm/system): the modern usage + legacy stream
                // type pair routes correctly and preserves focus/ducking. Unchanged path.
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usageForChannel(channel))
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setLegacyStreamType(stream)
                        .build());
            }
            if (headers != null) {
                mp.setDataSource(this, uri, headers);
            } else {
                mp.setDataSource(this, uri);
            }
            mp.setLooping(loop);
            mp.setOnPreparedListener(p -> {
                try { p.start(); Log.i(TAG, "playback started (loop=" + loop + ")"); }
                catch (Throwable t) { Log.w(TAG, "start failed: " + t.getMessage()); stopSelf(); }
            });
            if (!loop) mp.setOnCompletionListener(p -> stopSelf());
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                stopSelf();
                return true;
            });
            // Async prepare — this service has a real Looper (main thread), so the
            // onPrepared callback fires normally (unlike the daemon's Looper-less
            // worker threads, which is exactly why playback moved here).
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "setup failed: " + t.getMessage());
            stopSelf();
        }
    }

    /**
     * Speak {@code text} via TextToSpeech on {@code channel}. TTS needs a real app
     * Context + Looper (the whole reason this runs in the app process, not the daemon).
     * Lazy-inits the engine; if it's still initialising, the request is stashed and
     * flushed from the init callback. The foreground notification keeps us alive for the
     * duration; we self-stop when the utterance finishes.
     */
    private void speak(String text, String channel) {
        if (text == null || text.trim().isEmpty()) { stopSelf(); return; }
        requestFocus(channel, false);
        if (tts != null && ttsReady) {
            speakNow(text, channel);
            return;
        }
        // Stash until init completes (last request wins — a newer speak supersedes).
        pendingSpeak = text;
        pendingSpeakChannel = channel;
        if (tts == null) {
            tts = new TextToSpeech(getApplicationContext(), status -> {
                ttsReady = (status == TextToSpeech.SUCCESS);
                if (ttsReady) {
                    try { tts.setLanguage(Locale.getDefault()); } catch (Throwable ignored) {}
                    String pend = pendingSpeak;
                    String pendCh = pendingSpeakChannel;
                    pendingSpeak = null;
                    if (pend != null) speakNow(pend, pendCh);
                } else {
                    Log.w(TAG, "TTS init failed (status=" + status + ")");
                    stopSelf();
                }
            });
        }
    }

    private void speakNow(String text, String channel) {
        try {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, streamForChannel(channel));
            String uttId = "overdrive-tts";
            tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { stopSelf(); }
                @Override public void onError(String id) { stopSelf(); }
            });
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, uttId);
            Log.i(TAG, "speak (" + channel + "): " + (text.length() > 40 ? text.substring(0, 40) + "…" : text));
        } catch (Throwable t) {
            Log.w(TAG, "speak failed: " + t.getMessage());
            stopSelf();
        }
    }

    private Map<String, String> authHeaders() {
        Map<String, String> h = new HashMap<>();
        try {
            if (AuthManager.getState() == null) AuthManager.initialize();
            String jwt = AuthManager.generateJwt();
            if (jwt != null) h.put("Cookie", "byd_session=" + jwt);
        } catch (Throwable t) {
            Log.w(TAG, "auth header build failed: " + t.getMessage());
        }
        return h;
    }

    private void requestFocus(String channel, boolean loop) {
        if (audioManager == null) return;
        abandonFocus();
        try {
            int gain = loop ? AudioManager.AUDIOFOCUS_GAIN
                            : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK;
            focusListener = fc -> { /* best-effort; don't pause on transient loss */ };
            if (Build.VERSION.SDK_INT >= 26) {
                android.media.AudioFocusRequest req = new android.media.AudioFocusRequest.Builder(gain)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(usageForChannel(channel))
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setOnAudioFocusChangeListener(focusListener)
                        .build();
                audioManager.requestAudioFocus(req);
                audioFocusRequest = req;
            } else {
                audioManager.requestAudioFocus(focusListener, streamForChannel(channel), gain);
                audioFocusRequest = Boolean.TRUE;
            }
        } catch (Throwable t) {
            Log.w(TAG, "requestAudioFocus failed: " + t.getMessage());
        }
    }

    private void abandonFocus() {
        if (audioManager == null || audioFocusRequest == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest instanceof android.media.AudioFocusRequest) {
                audioManager.abandonAudioFocusRequest((android.media.AudioFocusRequest) audioFocusRequest);
            } else if (focusListener != null) {
                audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) {
        } finally { audioFocusRequest = null; focusListener = null; }
    }

    private void releasePlayer() {
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (Throwable ignored) {}
            try { player.release(); } catch (Throwable ignored) {}
            player = null;
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();
        releasePlayer();
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Throwable ignored) {}
            tts = null; ttsReady = false;
        }
        abandonFocus();
        if (stopReceiverRegistered) {
            try { unregisterReceiver(stopReceiver); } catch (Throwable ignored) {}
            stopReceiverRegistered = false;
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static int usageForChannel(String channel) {
        if (channel == null) return AudioAttributes.USAGE_MEDIA;
        switch (channel.trim().toLowerCase()) {
            case "navigation": return AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
            case "voice":
            case "assistant":  return AudioAttributes.USAGE_ASSISTANT;
            case "phone":
            case "call":       return AudioAttributes.USAGE_VOICE_COMMUNICATION;
            case "alarm":      return AudioAttributes.USAGE_ALARM;
            case "system":     return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            case "media":
            default:           return AudioAttributes.USAGE_MEDIA;
        }
    }

    private static int streamForChannel(String channel) {
        if (channel == null) return AudioManager.STREAM_MUSIC;
        switch (channel.trim().toLowerCase()) {
            case "phone":
            case "call":       return AudioManager.STREAM_VOICE_CALL;
            case "alarm":      return AudioManager.STREAM_ALARM;
            case "system":     return AudioManager.STREAM_SYSTEM;
            case "ring":       return AudioManager.STREAM_RING;
            // Navigation / voice guidance ride the OEM-EXTENDED streams (STREAM_NAVI=14,
            // OEM voice=16), which is where the head unit's own nav prompts/TTS play and
            // which the "navigation volume" control adjusts (OEM firmware setBroadcastVolume
            // uses stream 14, setVoiceVolume 16). These are reached via the DIRECT
            // MediaPlayer.setAudioStreamType path (see startPlayback + isOemExtendedStream);
            // the previous STREAM_MUSIC fallback made nav audio physically identical to
            // media (the reported "doesn't reach the nav channel" bug).
            case "navigation": return STREAM_NAVI;
            case "voice":
            case "assistant":  return STREAM_VOICE_OEM;
            default:           return AudioManager.STREAM_MUSIC;
        }
    }

    // OEM-extended (non-public) BYD stream ints. STREAM_NAVI(14) = navigation guidance,
    // STREAM_VOICE_OEM(16) = voice/assistant. Not part of the public AudioManager contract,
    // so they must be applied via the deprecated-but-working MediaPlayer.setAudioStreamType
    // (the reference implementation's proven path), NOT AudioAttributes.setLegacyStreamType.
    private static final int STREAM_NAVI = 14;
    private static final int STREAM_VOICE_OEM = 16;

    /** True for the OEM-extended stream ints that need the direct setAudioStreamType path. */
    private static boolean isOemExtendedStream(int stream) {
        return stream == STREAM_NAVI || stream == STREAM_VOICE_OEM;
    }

    private static String orDefault(String s, String def) {
        return (s == null || s.trim().isEmpty()) ? def : s;
    }

    private void startForegroundCompat() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t.getMessage());
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("Playing audio")
                .setContentText("OverDrive automation")
                .setSmallIcon(R.drawable.ic_play_circle)
                .setOngoing(true)
                .setGroup(DaemonKeepaliveService.NOTIFICATION_GROUP_KEY)
                .build();
    }
}
