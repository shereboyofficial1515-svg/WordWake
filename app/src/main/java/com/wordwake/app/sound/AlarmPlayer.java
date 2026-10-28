package com.wordwake.app.sound;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

/**
 * Plays alarm audio on the alarm stream with audio focus handling.
 *
 * Fallback chain when the selected sound cannot be played: phone's default alarm sound, then the
 * bundled WordWake tone. Never changes the system volume; the per-message volume is applied to
 * the player only (relative to the phone's alarm volume).
 */
public final class AlarmPlayer {

    private static final String TAG = "WordWakeAudio";

    public static final int RESULT_SELECTED = 0;
    public static final int RESULT_FALLBACK = 1;
    public static final int RESULT_FAILED = 2;

    private final Context context;
    private final AudioManager audioManager;
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    private MediaPlayer player;
    private AudioFocusRequest focusRequest;
    private float volume = 1f;
    private boolean pausedByFocusLoss;
    private Runnable onFinished;

    public AlarmPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Starts playback. Stops anything this player was already playing, so sessions never overlap.
     *
     * @param onFinished called when a non-looping sound completes (may be null).
     */
    public int start(String uri, boolean loop, int volumePercent, Runnable onFinished) {
        stop();
        this.volume = Math.max(0f, Math.min(1f, volumePercent / 100f));
        this.onFinished = onFinished;
        requestFocus();

        if (uri != null && !uri.isEmpty() && tryPlay(Uri.parse(uri), loop)) return RESULT_SELECTED;
        Log.w(TAG, "Selected sound unavailable; using fallback");
        Uri sys = SoundCatalog.systemDefaultAlarm(context);
        if (sys != null && tryPlay(sys, loop)) return RESULT_FALLBACK;
        if (tryPlay(Uri.parse(SoundCatalog.DEFAULT_BUILT_IN_URI), loop)) return RESULT_FALLBACK;
        Log.e(TAG, "No playable alarm sound");
        abandonFocus();
        return RESULT_FAILED;
    }

    private boolean tryPlay(Uri uri, boolean loop) {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(attrs);
            mp.setDataSource(context, uri);
            mp.setLooping(loop);
            mp.setVolume(volume, volume);
            mp.setOnErrorListener((m, what, extra) -> {
                Log.e(TAG, "MediaPlayer error " + what + "/" + extra);
                return false;
            });
            mp.setOnCompletionListener(m -> {
                if (!m.isLooping() && onFinished != null) onFinished.run();
            });
            mp.prepare();
            mp.start();
            player = mp;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Cannot play sound: " + e.getClass().getSimpleName());
            mp.release();
            return false;
        }
    }

    public boolean isPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Stops playback and releases every resource. Safe to call repeatedly. */
    public void stop() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            player.release();
            player = null;
        }
        pausedByFocusLoss = false;
        abandonFocus();
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        if (player == null) return;
        try {
            switch (change) {
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // e.g. an incoming phone call: pause instead of ringing over it.
                    if (player.isPlaying()) {
                        player.pause();
                        pausedByFocusLoss = true;
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    player.setVolume(volume * 0.3f, volume * 0.3f);
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    player.setVolume(volume, volume);
                    if (pausedByFocusLoss) {
                        player.start();
                        pausedByFocusLoss = false;
                    }
                    break;
                default:
                    break;
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "Focus change on released player");
        }
    };

    private void requestFocus() {
        if (audioManager == null) return;
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(false)
                .build();
        int r = audioManager.requestAudioFocus(focusRequest);
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Focus denied (typically during a call). An alarm still plays, at reduced volume.
            Log.i(TAG, "Audio focus not granted (" + r + "); playing quietly");
            volume = Math.min(volume, 0.25f);
        }
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    /** Alarm stream volume as a fraction of its maximum (0..1). */
    public static float alarmStreamLevel(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return 1f;
        int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
        if (max <= 0) return 1f;
        return am.getStreamVolume(AudioManager.STREAM_ALARM) / (float) max;
    }
}
