package com.metallic.chiaki.steam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.metallic.chiaki.lib.Session

/**
 * Captures the phone / wired-headset microphone and feeds it to the PS5 party chat.
 * 48 kHz, mono, 16-bit, in 10 ms (480 sample) frames — what the console expects.
 * Needs the RECORD_AUDIO permission and an app built with steamMic=true (Opus encoder).
 */
class MicCapture(private val session: Session)
{
    companion object
    {
        private const val TAG = "SteamMic"
        private const val RATE = 48000
        private const val FRAME = 480
    }

    @Volatile private var running = false
    @Volatile var muted = false
        private set
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun start(startMuted: Boolean): Boolean
    {
        if(running) return true
        if(!session.micInit())
        {
            Log.w(TAG, "Native mic not available (build with steamMic=true)")
            return false
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if(min <= 0) return false
        val rec = try
        {
            // MIC captures directly from the device or headset microphone.
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 8))
        }
        catch(e: Exception) { Log.e(TAG, "AudioRecord: ${e.message}"); return false }
        if(rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }

        record = rec
        muted = startMuted
        session.micMute(!muted)
        running = true
        rec.startRecording()
        thread = Thread({
            val buf = ShortArray(FRAME)
            while(running)
            {
                var got = 0
                while(got < FRAME && running)
                {
                    val n = rec.read(buf, got, FRAME - got)
                    if(n < 0) { running = false; break }
                    got += n
                }
                if(running && got == FRAME && !muted) session.micFrame(buf)
            }
        }, "steam-mic").also { it.start() }
        return true
    }

    fun setMuted(value: Boolean)
    {
        muted = value
        session.micMute(!value)
    }

    fun toggleMute(): Boolean { setMuted(!muted); return muted }

    fun stop()
    {
        running = false
        try { thread?.join(500) } catch(_: InterruptedException) {}
        thread = null
        record?.let { try { it.stop() } catch(_: Exception) {}; it.release() }
        record = null
    }
}
