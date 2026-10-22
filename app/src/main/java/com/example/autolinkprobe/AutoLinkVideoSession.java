package com.example.autolinkprobe;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.view.Surface;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Experimental AutoLink H.264 video source, activated only by user. */
final class AutoLinkVideoSession {
    interface Sink { void send(byte[] data) throws IOException; void log(String s); }
    static final int WIDTH = 1280, HEIGHT = 720, FPS = 30;
    private final Sink sink;
    private volatile boolean streaming;
    private volatile int channel = -1, pending = 0, maxPending = 1;
    private volatile int sent = 0, acked = 0;
    private int selectedConfig = 2;
    private boolean setupAccepted, focusGranted;
    // HU marks its post-auth frames with bit3 even though the captured payload parses
    // as clear protobuf. Mirroring that *flag* is an experiment, not actual TLS.
    private volatile boolean mirrorPeerFlag = true;
    private MediaCodec codec;
    private Surface surface;

    AutoLinkVideoSession(Sink sink) { this.sink = sink; }

    synchronized void setMirrorPeerFlag(boolean enabled) { mirrorPeerFlag = enabled; }
    boolean isMirrorPeerFlag() { return mirrorPeerFlag; }
    private int specificFlags() { return mirrorPeerFlag ? 0x0B : 0x03; }

    synchronized String state() {
        if (streaming) return "ВИДЕО ИДЁТ: frames=" + sent + " ack=" + acked;
        if (channel < 0) return "канал не открыт";
        if (setupAccepted) return "Setup принят, ожидание фокуса (ch=" + channel + ")";
        return "ожидание SetupResponse (ch=" + channel + ")";
    }

    synchronized void open(int id) {
        if (channel > 0 || id < 1 || id > 255) return;
        channel = id; selectedConfig = 2; pending = sent = acked = 0;
        setupAccepted = focusGranted = false;
        try {
            // Message 7 is CONTROL, but the channel is the TARGET wire channel.
            message(id, 0x07, 7, new byte[]{8, 0, 16, (byte) id});
            sink.log("VIDEO: CHANNEL_OPEN_REQUEST отправлен ch=" + id);
        } catch (IOException e) { error(e); }
    }

    synchronized void onRx(int ch, int id, byte[] data) {
        if (ch != channel || ch <= 0) return;
        try {
            if (id == 8 && !setupAccepted) {
                int status = field(data, 1, -1);
                sink.log("VIDEO: OPEN_RESPONSE status=" + status);
                if (status != 0) { stop(); return; }
                message(ch, specificFlags(), 0x8000, new byte[]{8, 3}); // H.264 Baseline
                sink.log("VIDEO: H264 SETUP отправлен с flag=0x"
                        + Integer.toHexString(specificFlags()) + "; жду ID 0x8003");
            } else if (id == 0x8003) {
                int status = field(data, 1, -1), credit = field(data, 2, 1);
                selectedConfig = Math.min(127, Math.max(0, field(data, 3, 0)));
                sink.log("VIDEO: SETUP_RESPONSE status=" + status + " credit=" + credit + " config=" + selectedConfig);
                if (status != 2) { stop(); return; }
                setupAccepted = true;
                maxPending = Math.max(1, Math.min(10, credit));
                // VideoFocusRequest: field2=PROJECTED(1), field3=USER_SELECTION(4).
                // There is no protobuf field1 in the actual schema.
                message(ch, specificFlags(), 0x8007, new byte[]{16, 1, 24, 4});
                sink.log("VIDEO: запросил видеорежим, жду VIDEO_FOCUS_INDICATION");
            } else if (id == 0x8008) {
                int focus = field(data, 1, 0);
                sink.log("VIDEO: FOCUS=" + focus);
                if (focus != 1) { if (streaming) stop(); return; }
                if (setupAccepted && !streaming) {
                    focusGranted = true;
                    message(ch, specificFlags(), 0x8001, new byte[]{8, 1, 16, (byte) selectedConfig});
                    sink.log("VIDEO: START отправлен, запускаю тестовую картинку");
                    startCodec();
                }
            } else if (id == 0x8004) {
                pending = Math.max(0, pending - Math.min(10, Math.max(1, field(data, 2, 1))));
                acked++;
                if (acked % 30 == 1) sink.log("VIDEO: ACK=" + acked + ", pending=" + pending);
            } else if (id == 0x8002) {
                sink.log("VIDEO: STOP от магнитолы");
                stop();
            }
        } catch (IOException e) { error(e); }
    }

    /** Parse the actual video channel from HU ServiceDiscoveryResponse protobuf. */
    static int findVideoChannel(byte[] sdp) {
        int[] p = {0};
        try {
            while (p[0] < sdp.length) {
                int tag = varint(sdp, p), wire = tag & 7;
                if (tag >>> 3 == 1 && wire == 2) {
                    byte[] desc = bytes(sdp, p), av = nested(desc, 3);
                    if (av != null && field(av, 1, -1) == 3) return field(desc, 1, -1);
                } else skip(sdp, p, wire);
            }
        } catch (RuntimeException ignored) { }
        return -1;
    }
    private static int field(byte[] b, int target, int fallback) {
        int[] p = {0};
        try {
            while (p[0] < b.length) {
                int tag = varint(b, p), wire = tag & 7;
                if (tag >>> 3 == target && wire == 0) return varint(b, p);
                skip(b, p, wire);
            }
        } catch (RuntimeException ignored) { }
        return fallback;
    }
    private static byte[] nested(byte[] b, int target) {
        int[] p = {0};
        try {
            while (p[0] < b.length) {
                int tag = varint(b, p), wire = tag & 7;
                if (tag >>> 3 == target && wire == 2) return bytes(b, p);
                skip(b, p, wire);
            }
        } catch (RuntimeException ignored) { }
        return null;
    }
    private static int varint(byte[] b, int[] p) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (p[0] >= b.length) throw new IllegalArgumentException();
            int x = b[p[0]++] & 255;
            value |= (x & 127) << shift;
            if ((x & 128) == 0) return value;
        }
        throw new IllegalArgumentException();
    }
    private static byte[] bytes(byte[] b, int[] p) {
        int n = varint(b, p);
        if (n < 0 || n > b.length - p[0]) throw new IllegalArgumentException();
        byte[] out = Arrays.copyOfRange(b, p[0], p[0] + n); p[0] += n;
        return out;
    }
    private static void skip(byte[] b, int[] p, int wire) {
        if (wire == 0) varint(b, p);
        else if (wire == 2) {
            int n = varint(b, p);
            if (n < 0 || n > b.length - p[0]) throw new IllegalArgumentException();
            p[0] += n;
        } else if (wire == 1 || wire == 5) {
            int n = wire == 1 ? 8 : 4;
            if (n > b.length - p[0]) throw new IllegalArgumentException();
            p[0] += n;
        } else throw new IllegalArgumentException();
    }

    private void message(int ch, int flags, int id, byte[] payload) throws IOException {
        byte[] data = new byte[payload.length + 2];
        data[0] = (byte) (id >>> 8); data[1] = (byte) id;
        System.arraycopy(payload, 0, data, 2, payload.length);
        sendFrames(ch, flags, data);
        StringBuilder tx = new StringBuilder();
        for (byte b : data) tx.append(String.format(java.util.Locale.US, "%02X ", b & 255));
        sink.log(String.format(java.util.Locale.US,
                "VIDEO TX: ch=%d flags=0x%02X id=0x%04X payload=[%s]",
                ch, flags, id, tx.toString().trim()));
    }
    private void sendFrames(int ch, int flags, byte[] data) throws IOException {
        final int chunkMax = 16384;
        for (int pos = 0; pos < data.length;) {
            int n = Math.min(chunkMax, data.length - pos);
            int type = data.length <= chunkMax ? 3 : pos == 0 ? 1 : pos + n == data.length ? 2 : 0;
            boolean first = type == 1;
            byte[] frame = new byte[4 + n + (first ? 4 : 0)];
            frame[0] = (byte) ch; frame[1] = (byte) ((flags & ~3) | type);
            frame[2] = (byte) (n >>> 8); frame[3] = (byte) n;
            int at = 4;
            if (first) {
                frame[at++] = (byte) (data.length >>> 24);
                frame[at++] = (byte) (data.length >>> 16);
                frame[at++] = (byte) (data.length >>> 8);
                frame[at++] = (byte) data.length;
            }
            System.arraycopy(data, pos, frame, at, n);
            sink.send(frame);
            pos += n;
        }
    }

    private void startCodec() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1200000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            streaming = true;
            MediaCodec encoder = codec; Surface input = surface;
            new Thread(() -> draw(input), "autolink-testcard").start();
            new Thread(() -> drain(encoder), "autolink-video-tx").start();
        } catch (Exception e) { sink.log("VIDEO: MediaCodec ошибка: " + e); stop(); }
    }

    private void draw(Surface input) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] colors = {Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.WHITE, Color.DKGRAY};
        long index = 0;
        try {
            while (streaming) {
                long begin = SystemClock.elapsedRealtime();
                Canvas canvas = input.lockCanvas(null);
                for (int c = 0; c < colors.length; c++) {
                    paint.setColor(colors[c]);
                    canvas.drawRect(c * 160, 0, (c + 1) * 160, 720, paint);
                }
                paint.setColor(Color.BLACK); canvas.drawRect(80, 250, 1200, 450, paint);
                paint.setColor(Color.WHITE); paint.setTextSize(70);
                canvas.drawText("GAZ AutoLink H.264", 110, 333, paint);
                paint.setTextSize(42);
                canvas.drawText("1280x720 @ 30 FPS  Frame " + index++, 110, 406, paint);
                input.unlockCanvasAndPost(canvas);
                long left = 1000 / FPS - (SystemClock.elapsedRealtime() - begin);
                if (left > 0) Thread.sleep(left);
            }
        } catch (Exception e) { if (streaming) sink.log("VIDEO: Canvas ошибка: " + e); stop(); }
    }

    private void drain(MediaCodec encoder) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long initialTime = -1;
        try {
            while (streaming) {
                int id = encoder.dequeueOutputBuffer(info, 10000);
                if (id == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = encoder.getOutputFormat();
                    sendConfig(join(avc(format.getByteBuffer("csd-0")), avc(format.getByteBuffer("csd-1"))));
                } else if (id >= 0) {
                    ByteBuffer out = encoder.getOutputBuffer(id);
                    if (out != null && info.size > 0) {
                        out.position(info.offset); out.limit(info.offset + info.size);
                        byte[] bytes = new byte[info.size]; out.get(bytes);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) sendConfig(avc(ByteBuffer.wrap(bytes)));
                        else {
                            if (initialTime == -1) initialTime = info.presentationTimeUs;
                            sendMedia(avc(ByteBuffer.wrap(bytes)), Math.max(0L, info.presentationTimeUs - initialTime));
                        }
                    }
                    encoder.releaseOutputBuffer(id, false);
                }
            }
        } catch (Exception e) { if (streaming) sink.log("VIDEO: Output ошибка: " + e); stop(); }
    }

    private static byte[] avc(ByteBuffer buffer) {
        if (buffer == null) return new byte[0];
        ByteBuffer copy = buffer.duplicate();
        byte[] data = new byte[copy.remaining()]; copy.get(data);
        if (data.length < 4 || (data[0] == 0 && data[1] == 0 &&
                (data[2] == 1 || (data[2] == 0 && data[3] == 1)))) return data;
        ByteArrayOutputStream converted = new ByteArrayOutputStream();
        for (int i = 0; i + 4 <= data.length;) {
            int n = ((data[i] & 255) << 24) | ((data[i + 1] & 255) << 16) |
                    ((data[i + 2] & 255) << 8) | (data[i + 3] & 255);
            i += 4;
            if (n < 1 || n > data.length - i) return data;
            converted.write(0); converted.write(0); converted.write(0); converted.write(1);
            converted.write(data, i, n); i += n;
        }
        return converted.size() == 0 ? data : converted.toByteArray();
    }
    private static byte[] join(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length); return r;
    }
    private synchronized void sendConfig(byte[] h264) throws IOException {
        if (!streaming || h264.length == 0) return;
        byte[] data = new byte[h264.length + 2]; data[1] = 1;
        System.arraycopy(h264, 0, data, 2, h264.length);
        sendFrames(channel, specificFlags(), data);
        sink.log("VIDEO: SPS/PPS отправлены " + h264.length + " байт");
    }
    private synchronized void sendMedia(byte[] h264, long pts) throws IOException {
        if (!streaming || !focusGranted || pending >= maxPending || h264.length == 0) return;
        byte[] data = new byte[h264.length + 10];
        for (int i = 0; i < 8; i++) data[2 + i] = (byte) (pts >>> (56 - i * 8));
        System.arraycopy(h264, 0, data, 10, h264.length);
        sendFrames(channel, specificFlags(), data); pending++; sent++;
        if (sent % 30 == 1) sink.log("VIDEO: H264 кадр=" + sent + " bytes=" + h264.length + " pts=" + pts);
    }
    private void error(Exception e) { sink.log("VIDEO: ошибка USB/протокола: " + e); stop(); }
    synchronized void stop() {
        streaming = false; setupAccepted = focusGranted = false; channel = -1;
        MediaCodec releasing = codec; codec = null;
        Surface oldSurface = surface; surface = null;
        if (releasing != null || oldSurface != null) new Thread(() -> {
            if (releasing != null) {
                try { releasing.stop(); } catch (Exception ignored) { }
                try { releasing.release(); } catch (Exception ignored) { }
            }
            if (oldSurface != null) oldSurface.release();
        }, "autolink-codec-cleanup").start();
    }
}
