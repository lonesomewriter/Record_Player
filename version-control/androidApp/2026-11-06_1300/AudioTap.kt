package dev.recorddisc.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The record's shine visualizer on Android (2026-11-05): Media3's TeeAudioProcessor hands over a
 * copy of the sound ExoPlayer is about to play, which is analysed here only while the visualizer is
 * showing. No microphone and no permission (unlike Android's Visualizer API). The copy is taken
 * before the audio output's own buffer, so frames are shown about [OUTPUT_DELAY_MS] later.
 */
@OptIn(UnstableApi::class)
object AudioTap : TeeAudioProcessor.AudioBufferSink {
    private const val OUTPUT_DELAY_MS = 150L

    /** Read by the app's model (AndroidPlayer.spectrum) and fed from ExoPlayer's playback thread. */
    val spectrum = SpectrumHub(OUTPUT_DELAY_MS)

    @Volatile private var analyzer: SpectrumAnalyzer? = null
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_INVALID
    private var mono = FloatArray(4_096)
    private var wasEnabled = false

    /** The renderers factory for PlaybackService's ExoPlayer: Media3's default audio output plus this tap. */
    fun renderersFactory(context: Context): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParameters: Boolean): AudioSink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(this@AudioTap)))
                .build()
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        channels = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        analyzer = SpectrumAnalyzer(sampleRateHz) { spectrum.publish(it) }
        spectrum.clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!spectrum.enabled) { wasEnabled = false; return }
        val analyzer = analyzer ?: return
        if (!wasEnabled) { analyzer.reset(); wasEnabled = true }
        // Read without moving the buffer: the same bytes go on to the speaker.
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frames = when (encoding) {
            C.ENCODING_PCM_16BIT -> data.remaining() / (2 * channels)
            C.ENCODING_PCM_FLOAT -> data.remaining() / (4 * channels)
            else -> return // other encodings: no visualizer for this song
        }
        if (frames > mono.size) mono = FloatArray(frames)
        val base = data.position()
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (encoding == C.ENCODING_PCM_16BIT) data.getShort(base + (f * channels + c) * 2) / 32768f
                else data.getFloat(base + (f * channels + c) * 4)
            }
            mono[f] = sum / channels
        }
        analyzer.feed(mono, frames)
    }
}
