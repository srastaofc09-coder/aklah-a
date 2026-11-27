package com.aklah.ai;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Lightweight offline voice cleanup: low-frequency rumble reduction,
 * gentle noise gate and soft compression. This is DSP, not an AI denoiser.
 */
public final class OfflineVoiceEnhancer extends BaseAudioProcessor {
    private float[] previousInput = new float[2];
    private float[] previousOutput = new float[2];

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws AudioProcessor.UnhandledAudioFormatException {
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
                || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            int channels = Math.max(1, inputAudioFormat.channelCount);
            previousInput = new float[channels];
            previousOutput = new float[channels];
            return inputAudioFormat;
        }
        return AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!isActive() || !inputBuffer.hasRemaining()) return;

        ByteBuffer input = inputBuffer.duplicate().order(ByteOrder.nativeOrder());
        int bytes = input.remaining();
        ByteBuffer output = replaceOutputBuffer(bytes).order(ByteOrder.nativeOrder());
        int channels = Math.max(1, inputAudioFormat.channelCount);
        int sampleBytes = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
        int frames = bytes / sampleBytes / channels;
        float alpha = 0.985f;

        for (int frame = 0; frame < frames; frame++) {
            for (int channel = 0; channel < channels; channel++) {
                float x = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
                        ? input.getFloat() : input.getShort() / 32768f;

                float filtered = alpha * (previousOutput[channel] + x - previousInput[channel]);
                previousInput[channel] = x;
                previousOutput[channel] = filtered;

                float magnitude = Math.abs(filtered);
                float gate = magnitude < 0.010f ? 0.18f
                        : magnitude < 0.024f ? 0.18f + (magnitude - 0.010f) * 34f
                        : 1f;
                float value = filtered * gate * 1.12f;

                float abs = Math.abs(value);
                if (abs > 0.72f) {
                    float compressed = 0.72f + (abs - 0.72f) * 0.30f;
                    value = Math.copySign(compressed, value);
                }
                value = Math.max(-1f, Math.min(1f, value));

                if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                    output.putFloat(value);
                } else {
                    output.putShort((short) Math.round(value * 32767f));
                }
            }
        }

        while (input.hasRemaining()) output.put(input.get());
        inputBuffer.position(inputBuffer.limit());
        output.flip();
    }

    @Override
    protected void onFlush() {
        java.util.Arrays.fill(previousInput, 0f);
        java.util.Arrays.fill(previousOutput, 0f);
    }

    @Override
    protected void onReset() {
        java.util.Arrays.fill(previousInput, 0f);
        java.util.Arrays.fill(previousOutput, 0f);
    }
}
