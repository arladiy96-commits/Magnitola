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
    private final SpeedometerDashboard dashboard;
    private volatile boolean streaming;
    private volatile int channel = -1, pending = 0, maxPending = 1;
    private volatile int sent = 0, acked = 0;
    private int selectedConfig = 2;
    private boolean setupAccepted, focusGranted, startRequested;
    private boolean opened;
    private int lastAckValue = 0;
    private int configTransport = -1;

    // HU marks its post-auth frames with bit3 even though the captured payload parses
    // as clear protobuf. Mirroring that *flag* is an experiment, not actual TLS.
    private volatile boolean mirrorPeerFlag = true;
    private MediaCodec codec;
    private Surface surface;

    AutoLinkVideoSession(Sink sink, SpeedometerDashboard dashboard) {
        this.sink = sink;
        this.dashboard = dashboard;
    }

    synchronized void setMirrorPeerFlag(boolean enabled) { mirrorPeerFlag = enabled; }
    boolean isMirrorPeerFlag() { return mirrorPeerFlag; }
    private int specificFlags() { return mirrorPeerFlag ? 0x0B : 0x03; }

    synchronized String state() {
        if (streaming) return "ВИДЕО ИДЁТ: frames=" + sent + " ack=" + acked;
        if (channel < 0) return "канал не открыт";
        if (startRequested) return "Start отправлен, жду StartResponse(ID7) ch=" + channel;
        if (setupAccepted) return "Config получен, ch=" + channel;
        if (opened) return "Setup(ID2) отправлен, жду Config(ID5), ch=" + channel;
        return "ожидание CHANNEL_OPEN_RESPONSE(ID8), ch=" + channel;
    }

    synchronized void open(int id) {
        if (channel > 0 || id < 1 || id > 255) return;
        channel = id; selectedConfig = 0; pending = sent = acked = lastAckValue = 0;
        setupAccepted = focusGranted = startRequested = opened = false;
        configTransport = -1;
        try {
            // Message 7 is CONTROL, but the channel is the TARGET wire channel.
            message(id, 0x07, 7, new byte[]{8, 0, 16, (byte) id});
            sink.log("VIDEO: CHANNEL_OPEN_REQUEST отправлен ch=" + id);
        } catch (IOException e) { error(e); }
    }

    // Verified against original AutoLink 2.0.59 ARM64 libaljni.so:
    // MediaSourceBase sends Setup=2, Start=3, Stop=4, codec config=1, media=0.
    // Native MediaSourceBase::routeMessage receives Config=5, ACK=6,
    // StartResponse=7, StopResponse=8, and control failure=0x00FF.
    // VideoSource::routeMessage additionally receives FocusNotif=0x1001.
    synchronized void onRx(int ch, int id, byte[] data) {
        if (ch != channel || ch <= 0) return;
        try {
            if (id == 8 && !opened) {
                int status = field(data, 1, -1);
                sink.log("VIDEO: CHANNEL_OPEN_RESPONSE ID8 status=" + status);
                if (status != 0) { stop(); return; }
                opened = true;
                // Actual proprietary AutoLink ID=2 (NOT Android Auto 0x8000).
                message(ch, specificFlags(), 2, new byte[]{8, 3});
                sink.log("VIDEO: native SETUP ID2 (H264 codec=3), жду Config ID5");
            } else if (id == 5 && opened) {
                int status = field(data, 1, -1);
                int credit = field(data, 2, 1);
                selectedConfig = Math.max(0, Math.min(127, field(data, 3, 0)));
                configTransport = field(data, 4, -1);
                sink.log("VIDEO: native CONFIG ID5 status=" + status + " credit=" + credit
                        + " configIndex=" + selectedConfig + " transport=" + configTransport
                        + " raw=" + asHex(data));
                if (status != 2 || credit <= 0) {
                    sink.log("VIDEO: Config отклонён/некорректен, Start не отправляю");
                    stop();
                    return;
                }
                setupAccepted = true;
                maxPending = Math.max(1, Math.min(10, credit));
                if (configTransport == 5) {
                    sink.log("VIDEO: магнитола запросила RTP-транспорт (mode=5). "
                            + "USB H264 без RTP не запускаю: нужен отдельный транспорт");
                    return;
                }
                startNative();
            } else if (id == 7 && startRequested) {
                int accepted = field(data, 1, -1);
                sink.log("VIDEO: native START_RESPONSE ID7 accepted=" + accepted
                        + " raw=" + asHex(data));
                if (accepted != 1) {
                    sink.log("VIDEO: отказ Start — кодировщик не запускаю");
                    stop();
                    return;
                }
                focusGranted = true;
                startCodec();
                // Focus notification is a separate proprietary video message:
                // native VideoSource::sendVideoFocusRequest marshals ID 0x1000.
                message(ch, specificFlags(), 0x1000, new byte[]{8, 1, 16, 1, 24, 0});
                sink.log("VIDEO: запрошен native VideoFocus 0x1000 (PROJECTED)");
            } else if (id == 0x1001) {
                int focus = field(data, 1, -1);
                sink.log("VIDEO: native FOCUS_NOTIFICATION 0x1001, mode=" + focus
                        + " raw=" + asHex(data));
                if (focus != 1 && streaming) {
                    focusGranted = false;
                    sink.log("VIDEO: другой экран владеет фокусом; остановка кадров");
                } else if (focus == 1) {
                    focusGranted = true;
                }
            } else if (id == 6) {
                int n = field(data, 2, 0);
                int delta = n > lastAckValue ? n - lastAckValue : 1;
                lastAckValue = Math.max(lastAckValue, n);
                pending = Math.max(0, pending - Math.min(maxPending, delta));
                acked++;
                if (acked % 20 == 1) sink.log("VIDEO: native ACK ID6 count=" + n
                        + " ack=" + acked + " pending=" + pending);
            } else if (id == 0xff) {
                sink.log("VIDEO: ERROR ID=0x00FF от магнитолы (без payload). "
                        + "Текущий этап: " + state()
                        + ". Это не ответ CONFIG; смотрим последний VIDEO TX.");
            } else if (id == 0x1003) {
                sink.log("VIDEO: native DISPLAY_CHANGE 0x1003 raw=" + asHex(data));
            } else if (id == 8) {
                sink.log("VIDEO: native STOP_RESPONSE ID8 raw=" + asHex(data));
            } else {
                sink.log("VIDEO: неизвестное native сообщение id=0x"
                        + Integer.toHexString(id) + " payload=" + asHex(data));
            }
        } catch (IOException e) { error(e); }
    }

    private static String asHex(byte[] data) {
        StringBuilder s = new StringBuilder();
        for (byte b : data) s.append(String.format(java.util.Locale.US, "%02X ", b & 255));
        return s.toString().trim();
    }

    /** MediaSourceBase::sendStart(session, index, width, height).
     *  Protobuf Start: field1=configIndex, field2=session, field3=width,
     *  field4=height. From verified JNI wrapper + native protobuf storage layout
     *  and Java call site VideoSource.sendStart(session,index,width,height).
     */
    private void startNative() throws IOException {
        if (startRequested || !setupAccepted) return;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        putIntField(payload, 1, selectedConfig);
        putIntField(payload, 2, 1);
        putIntField(payload, 3, WIDTH);
        putIntField(payload, 4, HEIGHT);
        message(channel, specificFlags(), 3, payload.toByteArray());
        startRequested = true;
        sink.log("VIDEO: native START ID3; index=" + selectedConfig
                + " session=1 resolution=" + WIDTH + "x" + HEIGHT
                + " (жду START_RESPONSE ID7)");
    }

    private static void putIntField(ByteArrayOutputStream out, int field, int value) {
        out.write((field << 3));
        int v = value;
        while ((v & ~127) != 0) {
            out.write((v & 127) | 128);
            v >>>= 7;
        }
        out.write(v);
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
        final int chunkMax = 0x3f00; // matches native ChannelManager max fragment
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
            format.setInteger(MediaFormat.KEY_BIT_RATE, 3500000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            codec.start();
            streaming = true;
            MediaCodec encoder = codec; Surface input = surface;
            new Thread(() -> draw(input), "autolink-speedometer").start();
            new Thread(() -> drain(encoder), "autolink-video-tx").start();
        } catch (Exception e) { sink.log("VIDEO: MediaCodec ошибка: " + e); stop(); }
    }

    private void draw(Surface input) {
        try {
            while (streaming) {
                long begin = SystemClock.elapsedRealtime();
                Canvas canvas = input.lockCanvas(null);
                try {
                    dashboard.draw(canvas, canvas.getWidth(), canvas.getHeight());
                } finally {
                    input.unlockCanvasAndPost(canvas);
                }
                long left = 1000 / FPS - (SystemClock.elapsedRealtime() - begin);
                if (left > 0) Thread.sleep(left);
            }
        } catch (Exception e) {
            if (streaming) sink.log("VIDEO: ошибка отрисовки спидометра: " + e);
            stop();
        }
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
        streaming = false; setupAccepted = focusGranted = startRequested = opened = false;
        channel = -1;
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
