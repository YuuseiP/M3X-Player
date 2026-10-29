package cn.yuusei.m3x;

import org.junit.Test;
import org.json.JSONObject;
import java.io.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class AiffHeaderTest {
    private byte[] file(int bits, String compression, int offset) throws Exception {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(chunks);
        // Odd-length unknown chunks must include a pad byte.
        out.writeBytes("NAME"); out.writeInt(3); out.writeBytes("abc"); out.writeByte(0);
        out.writeBytes("COMM"); out.writeInt(compression == null ? 18 : 24);
        out.writeShort(2); out.writeInt(44100); out.writeShort(bits);
        out.writeShort(0x400e); out.writeLong(0xac44000000000000L);
        if (compression != null) { out.writeBytes(compression); out.writeShort(0); }
        int size = 44100 * 2 * bits / 8;
        out.writeBytes("SSND"); out.writeInt(size + 8 + offset);
        out.writeInt(offset); out.writeInt(0); out.write(new byte[offset]); out.write(new byte[size]);
        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); out = new DataOutputStream(bytes);
        out.writeBytes("FORM"); out.writeInt(body.length + 4); out.writeBytes(compression == null ? "AIFF" : "AIFC");
        out.write(body); return bytes.toByteArray();
    }

    @Test public void readsPcmVariantsAndMetadataWithoutReadingPayload() throws Exception {
        for (int bits : new int[]{8,16,24,32}) for (String compression : new String[]{null,"NONE","twos","sowt"}) {
            byte[] bytes = file(bits, compression, 6);
            ByteArrayInputStream input = new ByteArrayInputStream(bytes);
            AiffHeader h = AiffHeader.read(input);
            assertEquals(44100, h.rate); assertEquals(44100, h.frames); assertEquals(bits, h.bits);
            assertEquals(2, h.channels); assertEquals(bits / 4, h.frameSize);
            assertEquals(bytes.length - h.dataStart, input.available());
            assertEquals(compression != null && compression.equals("sowt"), h.littleEndian);
            JSONObject track = new JSONObject(); TagReader.read(new ByteArrayInputStream(bytes), track);
            assertEquals(1.0, track.getDouble("duration"), 0);
            assertEquals(bits, track.getInt("bits")); assertEquals(44100, track.getInt("sampleRate"));
        }
    }

    @Test public void convertsSignedSamplesExactly() {
        AiffHeader h = new AiffHeader();
        h.bits = 24; byte[] pcm = {0x12,0x34,0x56,(byte)0x80,0,0};
        h.toLittleEndian(pcm, pcm.length);
        assertArrayEquals(new byte[]{0x56,0x34,0x12,0,0,(byte)0x80}, pcm);
        h.bits = 16; pcm = new byte[]{0x12,0x34,(byte)0x80,0}; h.toLittleEndian(pcm, pcm.length);
        assertArrayEquals(new byte[]{0x34,0x12,0,(byte)0x80}, pcm);
        h.bits = 32; pcm = new byte[]{0x12,0x34,0x56,0x78}; h.toLittleEndian(pcm, pcm.length);
        assertArrayEquals(new byte[]{0x78,0x56,0x34,0x12}, pcm);
        h.littleEndian = true; h.toLittleEndian(pcm, pcm.length);
        assertArrayEquals(new byte[]{0x78,0x56,0x34,0x12}, pcm);
        h.bits = 8; pcm = new byte[]{(byte)0x80,0,0x7f}; h.toLittleEndian(pcm, pcm.length);
        assertArrayEquals(new byte[]{0,(byte)0x80,(byte)0xff}, pcm);
    }

    @Test public void rejectsInvalidOrCompressedHeaders() throws Exception {
        byte[] valid = file(16, null, 0);
        byte[] overrun = valid.clone(); Arrays.fill(overrun, 16, 20, (byte)0x7f);
        byte[] badOffset = valid.clone(); Arrays.fill(badOffset, 58, 62, (byte)0x7f);
        for (byte[] bytes : new byte[][]{Arrays.copyOf(valid, 20), overrun, badOffset, file(12,null,0), file(16,"fl32",0)}) {
            try { AiffHeader.read(new ByteArrayInputStream(bytes)); fail("Invalid AIFF accepted"); }
            catch (IOException expected) { }
        }
    }
}
