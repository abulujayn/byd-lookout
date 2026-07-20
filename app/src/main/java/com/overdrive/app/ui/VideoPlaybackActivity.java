package com.overdrive.app.ui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.overdrive.app.ui.view.ZoomableVideoView;

/**
 * Fullscreen video player for the "Play Video" automation / key-mapping action.
 *
 * <p><b>Why an app-process Activity.</b> The daemon (UID 2000, {@code app_process})
 * cannot render video — a daemon MediaPlayer's {@code prepare()} fails with
 * {@code status=0x80000000} (see {@link com.overdrive.app.byd.AudioPlaybackController}),
 * and the daemon-owned SurfaceControl lane is heavyweight and reserved for the safety
 * overlays. A normal Activity plays in the real app process where MediaPlayer works, and
 * is far more intuitive (a real fullscreen player the user can dismiss) than compositing a
 * video onto a system layer.
 *
 * <p><b>Why {@link ZoomableVideoView}, not {@code VideoView}.</b> On this BYD DiLink
 * Android-10 head unit, {@code VideoView} (which wraps a {@code SurfaceView}) prepared and
 * played but never composited a visible frame — the reported "Play Video shows a blank
 * black screen while the audio plays". The app's own recordings player already proved this
 * and switched to a {@code TextureView}-backed player ({@link ZoomableVideoView}), which
 * routes frames through the view compositor and renders correctly on this unit. Reusing it
 * here fixes the blank video with the same setVideoURI/listener/start surface.
 *
 * <p>Launched by the daemon via {@code am start -n .../VideoPlaybackActivity} with
 * extras: {@code libName} OR {@code filePath}, {@code channel}, {@code loop}. A library
 * file streams from the daemon's authenticated {@code /api/audio/library/raw} (the app
 * can't read {@code /data/local/tmp}); an external path is opened directly. Tapping the
 * screen, back, or the {@link #ACTION_STOP} broadcast (from Stop Audio) finishes it.
 */
public final class VideoPlaybackActivity extends Activity {

    private static final String TAG = "VideoPlaybackActivity";
    private static final String DAEMON_BASE = "http://127.0.0.1:8080";
    /** Same stop broadcast the audio service + daemon stop() use. */
    public static final String ACTION_STOP = "com.overdrive.app.action.STOP_MEDIA";

    private ZoomableVideoView videoView;
    private boolean stopReceiverRegistered;

    private final BroadcastReceiver stopReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { finish(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Diagnostic: proves whether the activity was actually created (vs the launch being
        // dropped before onCreate — the real question behind "Play Video does nothing").
        Log.i(TAG, "onCreate reached; intent extras libName=" + getIntent().getStringExtra("libName")
                + " filePath=" + getIntent().getStringExtra("filePath"));

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        videoView = new ZoomableVideoView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = android.view.Gravity.CENTER;
        videoView.setLayoutParams(lp);
        root.addView(videoView);
        setContentView(root);

        // Tap anywhere to dismiss (a played video shouldn't trap the user).
        root.setOnClickListener(v -> finish());

        hideSystemUi(root);
        registerReceiver(stopReceiver, new IntentFilter(ACTION_STOP));
        stopReceiverRegistered = true;

        startFromIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startFromIntent(intent); // a new play replaces the current one
    }

    private void startFromIntent(Intent intent) {
        if (intent == null) { finish(); return; }
        final boolean loop = intent.getBooleanExtra("loop", false);
        final String channel = intent.getStringExtra("channel");
        String libName = intent.getStringExtra("libName");
        String filePath = intent.getStringExtra("filePath");

        Uri uri;
        if (libName != null && !libName.isEmpty()) {
            // Library file streams from the daemon over LOOPBACK (127.0.0.1); the auth
            // middleware's loopback safety net trusts 127.0.0.1 with no tunnel headers, so
            // no auth cookie is needed (ZoomableVideoView.setVideoURI has no header overload,
            // and the raw endpoint is Range-aware so the MediaPlayer can seek the moov atom).
            uri = Uri.parse(DAEMON_BASE + "/api/audio/library/raw?name=" + Uri.encode(libName));
        } else if (filePath != null && !filePath.isEmpty()) {
            uri = Uri.fromFile(new java.io.File(filePath));
        } else {
            Log.w(TAG, "no libName/filePath — nothing to play");
            finish();
            return;
        }

        try {
            videoView.setOnPreparedListener(mp -> {
                try {
                    mp.setLooping(loop);
                    // Route the audio to the chosen channel. ZoomableVideoView otherwise
                    // uses MediaPlayer defaults (USAGE_MEDIA); overriding here after prepare
                    // keeps the picture path (which the TextureView owns) untouched. The OEM
                    // routes by legacy stream, so setLegacyStreamType is the lever (nav/voice
                    // are approximated to media for the picture-carrying video path).
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usageForChannel(channel))
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .setLegacyStreamType(streamForChannel(channel))
                            .build());
                } catch (Throwable ignored) {}
                videoView.start();
            });
            // One-shot finishes when the clip ends; a looping clip never completes.
            videoView.setOnCompletionListener(mp -> { if (!loop) finish(); });
            videoView.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "ZoomableVideoView error what=" + what + " extra=" + extra);
                finish();
                return true;
            });
            Log.i(TAG, "setVideoURI " + uri);
            videoView.setVideoURI(uri);
            videoView.requestFocus();
        } catch (Throwable t) {
            Log.w(TAG, "setup failed: " + t.getMessage());
            finish();
        }
    }

    private static int usageForChannel(String channel) {
        if (channel == null) return AudioAttributes.USAGE_MEDIA;
        switch (channel.trim().toLowerCase()) {
            case "navigation": return AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
            case "voice":      return AudioAttributes.USAGE_ASSISTANT;
            case "alarm":      return AudioAttributes.USAGE_ALARM;
            case "media":
            default:           return AudioAttributes.USAGE_MEDIA;
        }
    }

    /** Legacy stream type for a channel — this head unit routes audio by stream, not by
     *  usage (see MediaPlaybackService). Public streams only; nav/voice ride STREAM_MUSIC
     *  (STREAM_NOTIFICATION is not an audible path on this HU — matches MediaPlaybackService). */
    private static int streamForChannel(String channel) {
        if (channel == null) return android.media.AudioManager.STREAM_MUSIC;
        switch (channel.trim().toLowerCase()) {
            case "phone":
            case "call":       return android.media.AudioManager.STREAM_VOICE_CALL;
            case "alarm":      return android.media.AudioManager.STREAM_ALARM;
            case "system":     return android.media.AudioManager.STREAM_SYSTEM;
            case "ring":       return android.media.AudioManager.STREAM_RING;
            case "navigation":
            case "voice":
            case "assistant":  return android.media.AudioManager.STREAM_MUSIC;
            default:           return android.media.AudioManager.STREAM_MUSIC;
        }
    }

    private void hideSystemUi(View v) {
        v.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (stopReceiverRegistered) {
            try { unregisterReceiver(stopReceiver); } catch (Throwable ignored) {}
            stopReceiverRegistered = false;
        }
        try { if (videoView != null) videoView.stopPlayback(); } catch (Throwable ignored) {}
    }
}
