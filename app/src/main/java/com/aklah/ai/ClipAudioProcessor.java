package com.aklah.ai;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Per-clip gain and linear fade processor used by MP4 export. */
public final class ClipAudioProcessor extends BaseAudioProcessor {

    private final float volume;
    private final long fadeInUs;
    private final long fadeOutUs;
    private final long durationUs;
    private long processedFrames;

    public ClipAudioProcessor(float volume, long fadeInUs, long fadeOutUs,
                              long durationUs) {
        this.volume = Math.max(0f, Math.min(1f, volume));
        this.fadeInUs = Math.max(0L, fadeInUs);
        this.fadeOutUs = Math.max(0L, fadeOutUs);
        this.durationUs = durationUs;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws AudioProcessor.UnhandledAudioFormatException {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
                || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            return inputAudioFormat;
        }
        return AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!isActive() || !inputBuffer.hasRemaining()) {
            return;
        }
        ByteBuffer input = inputBuffer.duplicate().order(ByteOrder.nativeOrder());
        int bytes = input.remaining();
        ByteBuffer output = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder());
        int channels = Math.max(1, inputAudioFormat.channelCount);
        int sampleBytes = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
        int samples = bytes / sampleBytes;
        int frames = samples / channels;
        int sampleRate = Math.max(1, inputAudioFormat.sampleRate);

        for (int frame = 0; frame < frames; frame++) {
            long sampleTimeUs = (processedFrames + frame) * 1000000L / sampleRate;
            float gain = volume;
            if (fadeInUs > 0L) {
                gain *= Math.min(1f, sampleTimeUs / (float) fadeInUs);
            }
            if (fadeOutUs > 0L && durationUs > 0L && durationUs != C.TIME_UNSET) {
                long fadeStartUs = Math.max(0L, durationUs - fadeOutUs);
                if (sampleTimeUs >= fadeStartUs) {
                    gain *= Math.max(0f, Math.min(1f,
                            (durationUs - sampleTimeUs) / (float) fadeOutUs));
                }
            }
            for (int channel = 0; channel < channels; channel++) {
                if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                    float value = input.getFloat();
                    output.putFloat(Math.max(-1f, Math.min(1f, value * gain)));
                } else {
                    int value = input.getShort();
                    int scaled = Math.round(value * gain);
                    scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
                    output.putShort((short) scaled);
                }
            }
        }
        // Preserve any incomplete trailing bytes unchanged.
        while (input.hasRemaining()) output.put(input.get());
        processedFrames += frames;
        inputBuffer.position(inputBuffer.limit());
        output.flip();
    }

    @Override
    protected void onFlush(AudioProcessor.StreamMetadata streamMetadata) {
        processedFrames = 0L;
    }

    @Override
    protected void onReset() {
        processedFrames = 0L;
    }
}
