package com.bbsvoice.tts.edge;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decodes the mp3 the Edge service streams into the 16 bit PCM BBS can play.
 *
 * <p>Necessary because the two formats are imposed from both sides: the Edge endpoint only offers mp3
 * or WebM/Opus (measured, see {@link EdgeTtsClient#OUTPUT_FORMAT}), while BBS' {@code AudioReader}
 * only understands {@code .wav} and {@code .ogg} (Vorbis) — and nothing in Java or Minecraft decodes
 * mp3. So the pipeline is mp3 in, decoded PCM out, wrapped by {@code WavFile}.</p>
 *
 * <p>Backed by JLayer, the reference pure-Java mp3 decoder (LGPL, embedded in the mod jar). It is a
 * cold, deterministic, offline step: the same bytes always decode to the same samples, which is what
 * the voice cache relies on.</p>
 */
public final class Mp3Decoder
{
    private Mp3Decoder()
    {}

    /** Decoded audio, mono or interleaved, at the rate the stream declares (24000 for Edge). */
    public static final class Pcm
    {
        public final short[] samples;
        public final int sampleRate;

        Pcm(short[] samples, int sampleRate)
        {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }

        public float durationSeconds()
        {
            return this.sampleRate <= 0 ? 0F : (float) this.samples.length / (float) this.sampleRate;
        }
    }

    /**
     * @param mp3 a complete mp3 stream (an ID3 tag is not required, and Edge does not send one)
     * @throws IOException when nothing decodable was found
     */
    public static Pcm decode(byte[] mp3) throws IOException
    {
        if (mp3 == null || mp3.length == 0)
        {
            throw new IOException("no mp3 data to decode");
        }

        Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3));
        Decoder decoder = new Decoder();
        GrowableShorts samples = new GrowableShorts();
        int sampleRate = 0;

        try
        {
            while (true)
            {
                Header header = bitstream.readFrame();

                if (header == null)
                {
                    break;
                }

                SampleBuffer buffer = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                samples.write(buffer.getBuffer(), buffer.getBufferLength());
                sampleRate = buffer.getSampleFrequency();

                bitstream.closeFrame();
            }
        }
        catch (Exception e)
        {
            throw new IOException("could not decode the mp3 stream: " + e, e);
        }
        finally
        {
            try
            {
                bitstream.close();
            }
            catch (Exception ignored)
            {}
        }

        if (samples.size() == 0)
        {
            throw new IOException("the mp3 stream contained no decodable frames");
        }

        if (sampleRate <= 0)
        {
            sampleRate = 24000;
        }

        return new Pcm(samples.toArray(), sampleRate);
    }

    /** {@link java.io.ByteArrayOutputStream} that hands back a {@code short[]}. */
    private static final class GrowableShorts
    {
        private short[] buffer = new short[8192];
        private int size;

        void write(short[] source, int length)
        {
            int count = Math.min(length, source.length);

            if (this.size + count > this.buffer.length)
            {
                short[] grown = new short[Math.max(this.buffer.length * 2, this.size + count)];

                System.arraycopy(this.buffer, 0, grown, 0, this.size);
                this.buffer = grown;
            }

            System.arraycopy(source, 0, this.buffer, this.size, count);
            this.size += count;
        }

        int size()
        {
            return this.size;
        }

        short[] toArray()
        {
            short[] out = new short[this.size];

            System.arraycopy(this.buffer, 0, out, 0, this.size);

            return out;
        }
    }
}
