package com.bbsvoice.tts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal, dependency free 16 bit PCM WAV writer/reader. BBS' own {@code WaveReader} understands
 * exactly this layout (RIFF / WAVE / fmt / data), so the rendered cache files load straight into
 * BBS' sound manager.
 */
public final class WavFile
{
    private WavFile()
    {}

    public static void write(File file, short[] samples, int sampleRate) throws IOException
    {
        try (FileOutputStream stream = new FileOutputStream(file))
        {
            write(stream, samples, sampleRate);
        }
    }

    public static void write(OutputStream stream, short[] samples, int sampleRate) throws IOException
    {
        int dataLength = samples.length * 2;
        int byteRate = sampleRate * 2;

        stream.write("RIFF".getBytes("US-ASCII"));
        writeIntLE(stream, 36 + dataLength);
        stream.write("WAVE".getBytes("US-ASCII"));

        stream.write("fmt ".getBytes("US-ASCII"));
        writeIntLE(stream, 16);
        writeShortLE(stream, 1);            /* PCM */
        writeShortLE(stream, 1);            /* mono */
        writeIntLE(stream, sampleRate);
        writeIntLE(stream, byteRate);
        writeShortLE(stream, 2);            /* block align */
        writeShortLE(stream, 16);           /* bits per sample */

        stream.write("data".getBytes("US-ASCII"));
        writeIntLE(stream, dataLength);

        byte[] buffer = new byte[Math.max(2, samples.length * 2)];
        int i = 0;

        for (short sample : samples)
        {
            buffer[i] = (byte) (sample & 0xff);
            buffer[i + 1] = (byte) ((sample >> 8) & 0xff);
            i += 2;
        }

        stream.write(buffer);
        stream.flush();
    }

    /** Reads a simple 16 bit mono/stereo PCM wav back into mono samples (used by the tests). */
    public static short[] read(File file) throws IOException
    {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());

        if (bytes.length < 44)
        {
            return new short[0];
        }

        int channels = bytes[22] | (bytes[23] << 8);
        int bits = bytes[34] | (bytes[35] << 8);
        int offset = 12;

        while (offset + 8 <= bytes.length)
        {
            String id = new String(bytes, offset, 4, "US-ASCII");
            int size = (bytes[offset + 4] & 0xff) | ((bytes[offset + 5] & 0xff) << 8)
                | ((bytes[offset + 6] & 0xff) << 16) | ((bytes[offset + 7] & 0xff) << 24);

            if (id.equals("data"))
            {
                offset += 8;

                int frames = Math.min(size, bytes.length - offset) / (channels * (bits / 8));
                short[] out = new short[frames];

                for (int i = 0; i < frames; i++)
                {
                    int index = offset + i * channels * (bits / 8);
                    out[i] = (short) ((bytes[index] & 0xff) | (bytes[index + 1] << 8));
                }

                return out;
            }

            offset += 8 + size + (size & 1);
        }

        return new short[0];
    }

    /**
     * Reads how long a PCM wav plays for, from its header only.
     *
     * <p>The clip editor needs this the moment a line is finished: a voice clip has to be sized to its
     * line and the user has to be warned when the clip is shorter than the line. Decoding every sample
     * just to divide two numbers would make the render thread pay twice for the same file.</p>
     *
     * @return the duration in seconds, or 0 when the file is not a wav this class understands.
     */
    public static float seconds(File file)
    {
        try (InputStream stream = new FileInputStream(file))
        {
            byte[] head = stream.readNBytes(12);

            if (head.length < 12 || !"RIFF".equals(ascii(head, 0)) || !"WAVE".equals(ascii(head, 8)))
            {
                return 0F;
            }

            int sampleRate = 0;
            int channels = 1;
            int bits = 16;
            long dataSize = -1L;

            while (true)
            {
                byte[] chunk = stream.readNBytes(8);

                if (chunk.length < 8)
                {
                    break;
                }

                String id = ascii(chunk, 0);
                long size = readIntLE(chunk, 4) & 0xFFFFFFFFL;

                if ("data".equals(id))
                {
                    dataSize = size;

                    break;
                }

                if ("fmt ".equals(id))
                {
                    byte[] format = stream.readNBytes((int) Math.min(size, 64L));

                    if (format.length < 16)
                    {
                        return 0F;
                    }

                    channels = readShortLE(format, 2);
                    sampleRate = readIntLE(format, 4);
                    bits = readShortLE(format, 14);

                    if (!skip(stream, size - format.length + (size & 1L)))
                    {
                        return 0F;
                    }
                }
                else if (!skip(stream, size + (size & 1L)))
                {
                    return 0F;
                }
            }

            if (sampleRate <= 0 || channels <= 0 || bits <= 0 || dataSize <= 0L)
            {
                return 0F;
            }

            double frames = (double) dataSize / (channels * (bits / 8));

            return (float) (frames / sampleRate);
        }
        catch (IOException e)
        {
            return 0F;
        }
    }

    private static String ascii(byte[] bytes, int offset)
    {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static int readIntLE(byte[] bytes, int offset)
    {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static int readShortLE(byte[] bytes, int offset)
    {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    /** @return false when the stream ended before {@code count} bytes could be skipped. */
    private static boolean skip(InputStream stream, long count)
    {
        if (count <= 0L)
        {
            return true;
        }

        try
        {
            stream.skipNBytes(count);

            return true;
        }
        catch (IOException e)
        {
            return false;
        }
    }

    private static void writeIntLE(OutputStream stream, int value) throws IOException
    {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4);

        buffer.write(value & 0xff);
        buffer.write((value >> 8) & 0xff);
        buffer.write((value >> 16) & 0xff);
        buffer.write((value >> 24) & 0xff);
        buffer.writeTo(stream);
    }

    private static void writeShortLE(OutputStream stream, int value) throws IOException
    {
        stream.write(value & 0xff);
        stream.write((value >> 8) & 0xff);
    }
}
