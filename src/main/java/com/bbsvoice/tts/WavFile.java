package com.bbsvoice.tts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
        return read(java.nio.file.Files.readAllBytes(file.toPath()));
    }

    /** Reads a wav held in memory — which is how a service's answer arrives. */
    public static short[] read(byte[] bytes)
    {
        if (bytes.length < 44)
        {
            return new short[0];
        }

        int channels = bytes[22] | (bytes[23] << 8);
        int bits = bytes[34] | (bytes[35] << 8);

        channels = channels > 0 ? channels : 1;
        bits = bits > 0 ? bits : 16;

        int offset = 12;

        while (offset + 8 <= bytes.length)
        {
            String id = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int size = intAt(bytes, offset + 4);

            if (id.equals("data"))
            {
                offset += 8;

                int frame = channels * (bits / 8);
                int frames = Math.min(size, bytes.length - offset) / frame;
                short[] out = new short[frames];

                for (int i = 0; i < frames; i++)
                {
                    int index = offset + i * frame;
                    out[i] = (short) ((bytes[index] & 0xff) | (bytes[index + 1] << 8));
                }

                return out;
            }

            offset += 8 + size + (size & 1);
        }

        return new short[0];
    }

    /** @return whether these bytes carry a PCM wav, decided by magic bytes. */
    public static boolean isWav(byte[] bytes)
    {
        return bytes.length >= 12
            && "RIFF".equals(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
            && "WAVE".equals(new String(bytes, 8, 4, StandardCharsets.US_ASCII));
    }

    /** @return the rate the wav was recorded at, or 24000 when the header cannot be read. */
    public static int sampleRate(byte[] bytes)
    {
        int offset = 12;

        while (offset + 24 <= bytes.length)
        {
            String id = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int size = intAt(bytes, offset + 4);

            if (id.equals("fmt "))
            {
                int rate = intAt(bytes, offset + 12);

                return rate > 0 ? rate : 24000;
            }

            offset += 8 + size + (size & 1);
        }

        return 24000;
    }

    private static int intAt(byte[] bytes, int offset)
    {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
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

    /** @return the loudest sample, 0 for silence. */
    public static int peak(short[] samples)
    {
        int peak = 0;

        for (short sample : samples)
        {
            int value = Math.abs((int) sample);

            if (value > peak)
            {
                peak = value;
            }
        }

        return peak;
    }

    /**
     * Rejects a truncated or non-wav file, so a failed render is never cached as a finished one.
     *
     * <p>The voice cache treats any file longer than a wav header as ready, which is what makes this a
     * gate rather than a nicety: a half written file would otherwise be served forever, and the line
     * would look permanently synthesised while playing nothing.</p>
     */
    public static void validate(File file) throws IOException
    {
        if (!file.isFile() || file.length() <= 44)
        {
            throw new IOException("wrote " + (file.isFile() ? file.length() : 0) + " bytes");
        }

        byte[] head = new byte[12];

        try (InputStream in = new FileInputStream(file))
        {
            if (in.read(head) != head.length)
            {
                throw new IOException("truncated wav header");
            }
        }

        if (!isWav(head))
        {
            throw new IOException("not a wav: " + new String(head, 0, 4, StandardCharsets.US_ASCII)
                + "/" + new String(head, 8, 4, StandardCharsets.US_ASCII));
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
