package com.bbsvoice.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WavFileTest
{
    @Test
    void roundTripsSixteenBitMono(@TempDir Path folder) throws Exception
    {
        short[] samples = new short[1024];

        for (int i = 0; i < samples.length; i++)
        {
            samples[i] = (short) (Math.sin(i / 16D) * 12000D);
        }

        File file = folder.resolve("test.wav").toFile();

        WavFile.write(file, samples, 22050);

        assertTrue(file.length() > 44, "a wave header plus data should be written");

        short[] read = WavFile.read(file);

        assertArrayEquals(samples, read, "BBS' WaveReader must be able to read what we write");
    }

    @Test
    void writesAValidRiffHeader(@TempDir Path folder) throws Exception
    {
        short[] samples = new short[] {0, 1000, -1000, 2000};

        File file = folder.resolve("header.wav").toFile();

        WavFile.write(file, samples, 44100);

        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());

        assertEquals("RIFF", new String(bytes, 0, 4, "US-ASCII"));
        assertEquals("WAVE", new String(bytes, 8, 4, "US-ASCII"));
        assertEquals("fmt ", new String(bytes, 12, 4, "US-ASCII"));
        assertEquals("data", new String(bytes, 36, 4, "US-ASCII"));
        assertEquals(44100, (bytes[24] & 0xff) | ((bytes[25] & 0xff) << 8) | ((bytes[26] & 0xff) << 16) | ((bytes[27] & 0xff) << 24));
        assertEquals(samples.length * 2, bytes.length - 44);
    }

    @Test
    void readsTheDurationBackFromTheHeader(@TempDir Path folder) throws Exception
    {
        File file = folder.resolve("second.wav").toFile();

        WavFile.write(file, new short[24000], 24000);

        assertEquals(1F, WavFile.seconds(file), 0.001F, "24000 frames at 24 kHz is one second");
    }

    @Test
    void skipsUnknownChunksBeforeTheData(@TempDir Path folder) throws Exception
    {
        File plain = folder.resolve("plain.wav").toFile();

        WavFile.write(plain, new short[24000], 24000);

        byte[] bytes = java.nio.file.Files.readAllBytes(plain.toPath());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        out.write(bytes, 0, 36);                        /* RIFF header plus the fmt chunk */
        out.write("LIST".getBytes("US-ASCII"));         /* a chunk BBS' own reader tolerates too */
        out.write(new byte[] {4, 0, 0, 0});
        out.write("abcd".getBytes("US-ASCII"));
        out.write(bytes, 36, bytes.length - 36);        /* data */

        File file = folder.resolve("with-list.wav").toFile();

        java.nio.file.Files.write(file.toPath(), out.toByteArray());

        assertEquals(1F, WavFile.seconds(file), 0.001F, "an unknown chunk between fmt and data must be skipped");
    }

    @Test
    void reportsNoDurationForSomethingThatIsNotAWav(@TempDir Path folder) throws Exception
    {
        File garbage = folder.resolve("garbage.wav").toFile();

        java.nio.file.Files.write(garbage.toPath(), new byte[64]);

        assertEquals(0F, WavFile.seconds(garbage), 0F);
        assertEquals(0F, WavFile.seconds(folder.resolve("missing.wav").toFile()), 0F);
        assertEquals(0F, WavFile.seconds(folder.resolve("empty.wav").toFile()), 0F);
    }
}
