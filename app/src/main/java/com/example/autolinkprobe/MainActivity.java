package com.example.autolinkprobe;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * Diagnostic AOA probe; only known handshake replies can be transmitted.
 */
public final class MainActivity extends Activity {
    private static final String USB_PERMISSION = "com.example.autolinkprobe.USB_PERMISSION";
    private static final int CREATE_LOG = 100;
    private static final int MAX_READ = 65536;
    private static final int MAX_LOG_CHARS = 160000;

    private UsbManager usb;
    private UsbAccessory selected;
    private ParcelFileDescriptor descriptor;
    private volatile boolean listening;
    private volatile int totalRead;
    private volatile int sessionId;
    private volatile boolean autoHandshake;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean versionRequestSeen;
    private volatile boolean versionReplySent;
    private volatile boolean encapsulatedSslSeen;
    private volatile boolean authReplySent;
    private volatile boolean authCompleteSeen;
    private volatile boolean discoveryRequestSent;
    private final ByteArrayOutputStream incomingFrames = new ByteArrayOutputStream();
    private TextView logView;
    private EditText scriptEditor;
    private final ProtocolScript script = new ProtocolScript();
    private volatile boolean scriptEnabled;
    private final StringBuilder log = new StringBuilder();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                append(granted ? "Разрешение USB получено" : "Доступ к USB не предоставлен");
                if (granted && selected != null && usb.hasPermission(selected)) {
                    openAccessory();
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                append("USB-аксессуар отключён");
                stopReading();
                selected = null;
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(margin, margin, margin, margin);
        scroll.addView(root);

        root.addView(label("AutoLink USB Probe"));
        root.addView(label("USB-диагностика. Пакеты отправляются только после явного запуска сценария или нажатия кнопок 3/4."));
        root.addView(label("Сценарий обмена (можно менять БЕЗ переустановки APK):"));
        scriptEditor = new EditText(this);
        scriptEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scriptEditor.setMinLines(5);
        scriptEditor.setMaxLines(9);
        scriptEditor.setTextSize(12);
        scriptEditor.setHorizontallyScrolling(false);
        scriptEditor.setText(getPreferences(MODE_PRIVATE).getString("usb_script", ProtocolScript.DEFAULT_SCRIPT));
        root.addView(scriptEditor);
        addButton(root, "Запустить сценарий (без пересборки APK)", this::runScript);
        addButton(root, "Остановить сценарий", this::stopScript);
        addButton(root, "Сбросить сценарий до проверенного", () -> {
            scriptEditor.setText(ProtocolScript.DEFAULT_SCRIPT);
            saveScript();
            append("Редактор восстановлен до известного сценария.");
        });
        addButton(root, "1. Найти USB-аксессуар", this::scan);
        addButton(root, "2. Слушать USB (не больше 64 КБ)", this::start);
        addButton(root, "Автотест: пройти 2 известных шага", this::enableAutoTest);
        addButton(root, "3. Ответить VERSION_RESPONSE 1.11 (один раз)", this::respondVersion);
        addButton(root, "4. Ответить AUTH_RESPONSE (status=0)", this::respondAuth);
        addButton(root, "5. Запросить сервисы магнитолы (SERVICE_DISCOVERY)", this::sendServiceDiscovery);
        addButton(root, "Остановить чтение", this::stopReading);
        addButton(root, "Сохранить лог в файл", this::exportLog);
        addButton(root, "Скопировать лог", () -> {
            String data;
            synchronized (log) { data = log.toString(); }
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("AutoLink USB log", data));
            Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show();
        });
        logView = label("Подключи телефон по USB и нажми «Найти USB-аксессуар».");
        logView.setTextIsSelectable(true);
        root.addView(logView);
        setContentView(scroll);

        IntentFilter events = new IntentFilter(USB_PERMISSION);
        events.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            // Permission result comes via our mutable, package-targeted PendingIntent.
            registerReceiver(receiver, events, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, events);
        }
        append("Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT);
        append("Стартовый intent: " + getIntent().getAction());
        scan();
        if (selected != null) {
            append("USB уже подключён при запуске — автоматически начинаю чтение.");
            start();
        } else {
            append("Ожидаю USB магнитолы. Лучше подключать кабель после запуска приложения.");
        }
    }

    private TextView label(String content) {
        TextView text = new TextView(this);
        text.setText(content);
        text.setTextSize(16);
        text.setPadding(0, 12, 0, 12);
        return text;
    }

    private void addButton(LinearLayout root, String name, Runnable action) {
        Button button = new Button(this);
        button.setText(name);
        button.setOnClickListener(view -> action.run());
        root.addView(button);
    }

    private void append(String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                .format(new Date()) + " " + message + "\n";
        final String snapshot;
        synchronized (log) {
            if (log.length() < MAX_LOG_CHARS) {
                log.append(line);
            }
            snapshot = log.toString();
        }
        runOnUiThread(() -> {
            if (logView != null) logView.setText(snapshot);
        });
    }

    private void scan() {
        UsbAccessory[] accessories = usb.getAccessoryList();
        if (accessories == null || accessories.length == 0) {
            selected = null;
            append("AOA-аксессуар не найден. Убедись, что магнитола запустила режим AutoLink.");
            return;
        }
        selected = null;
        for (UsbAccessory item : accessories) {
            append("Обнаружен USB: manufacturer=" + item.getManufacturer()
                    + "; model=" + item.getModel()
                    + "; version=" + item.getVersion()
                    + "; permission=" + usb.hasPermission(item));
            if ("Sunplus, Inc.".equals(item.getManufacturer())
                    && "DVD".equals(item.getModel())) {
                selected = item;
            }
        }
        if (selected == null) {
            append("Аксессуар обнаружен, но он не Sunplus, Inc. / DVD. Автоматически не открываем.");
        }
    }

    private void start() {
        if (listening) { append("Уже читаем USB"); return; }
        scan();
        if (selected == null) return;

        if (!usb.hasPermission(selected)) {
            Intent permissionRequest = new Intent(USB_PERMISSION).setPackage(getPackageName());
            PendingIntent callback = PendingIntent.getBroadcast(
                    this, 0, permissionRequest,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            append("Запрошено системное разрешение на USB");
            usb.requestPermission(selected, callback);
        } else {
            openAccessory();
        }
    }

    private synchronized void openAccessory() {
        if (selected == null || listening) return;
        try {
            descriptor = usb.openAccessory(selected);
            if (descriptor == null) {
                append("openAccessory вернул null: нет разрешения или порт занят");
                return;
            }
            final ParcelFileDescriptor opened = descriptor;
            final int thisSession = ++sessionId;
            listening = true;
            totalRead = 0;
            versionRequestSeen = false;
            versionReplySent = false;
            encapsulatedSslSeen = false;
            authReplySent = false;
            authCompleteSeen = false;
            discoveryRequestSent = false;
            incomingFrames.reset();
            if (scriptEnabled) script.onReconnect();
            append("Порт открыт, сессия " + thisSession + ". Читаем RX; передача только по известным запросам.");
            mainHandler.postDelayed(() -> {
                if (listening && sessionId == thisSession && totalRead == 0) {
                    append("USB открыт 7 секунд, но RX = 0 байт. Ничего не отправляем наугад. Переподключи кабель и открой AutoLink на магнитоле.");
                }
            }, 7000);
            mainHandler.postDelayed(() -> {
                if (listening && sessionId == thisSession && totalRead == 0) {
                    append("USB открыт 20 секунд, но RX = 0. Это ожидание данных, а НЕ ошибка разбора. Нужно новое подключение.");
                }
            }, 20000);
            new Thread(() -> {
                try (FileInputStream input = new FileInputStream(opened.getFileDescriptor())) {
                    byte[] buffer = new byte[1024];

                    int count;
                    while (listening && sessionId == thisSession && totalRead < MAX_READ
                            && (count = input.read(buffer)) != -1) {
                        totalRead += count;
                        int hexLength = Math.min(count, 256);
                        append("RX " + count + " байт: " + hex(buffer, hexLength)
                                + (count > hexLength ? " [остальное опущено]" : ""));
                        try {
                            processIncomingFrames(buffer, count);
                        } catch (RuntimeException ex) {
                            append("Ошибка разбора RX (сырые байты сохранены): " + ex);
                            incomingFrames.reset();
                        }
                    }
                } catch (IOException ex) {
                    if (listening) append("Ошибка чтения: " + ex.getMessage());
                } finally {
                    synchronized (MainActivity.this) {
                        if (sessionId == thisSession) {
                            listening = false;
                            append("Чтение завершено; принято " + totalRead + " байт");
                            closeDescriptor();
                        } else {
                            try { opened.close(); } catch (IOException ignored) {}
                            append("Предыдущая USB-сессия " + thisSession + " закрыта.");
                        }
                    }
                }
            }, "aoa-usb-reader").start();
        } catch (Exception ex) {
            append("USB open exception: " + ex.getMessage());
            stopReading();
        }
    }

    // Captured from the head unit twice; a matching request is required before TX.
    private static final byte[] VERSION_REQUEST = new byte[] {
            0x00, 0x03, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x00, 0x0A
    };

    // Original AutoLink 2.0.59 native Controller::handleVersionRequest builds:
    // payload 00 02 00 01 00 0B 00 00 (version response 1.11, status MATCH).
    // AA single-fragment plaintext framing adds 00 03 00 08.
    private static final byte[] VERSION_RESPONSE = new byte[] {
            0x00, 0x03, 0x00, 0x08, 0x00, 0x02, 0x00, 0x01,
            0x00, 0x0B, 0x00, 0x00
    };


    // Same message that followed VERSION_RESPONSE in the first successful field test.
    // Original libaljni.so: Controller::routeMessage(msg 3) logs "EncapsulatedSsl"
    // then calls sendAuthResponse(0). This is not a TLS ClientHello.
    private static final byte[] ENCAPSULATED_SSL = new byte[] {
            0x00, 0x03, 0x00, 0x04, 0x00, 0x03, 0x00, 0x00
    };
    // Controller::sendAuthResponse(0) -> protobuf AuthResponse { status: 0 }
    // serialized as field 1 varint 0 => 08 00 with message ID 00 04.
    private static final byte[] AUTH_RESPONSE = new byte[] {
            0x00, 0x03, 0x00, 0x04, 0x00, 0x04, 0x08, 0x00
    };

    // USB reads may split or coalesce frames, so frame boundaries must
    // be reconstructed instead of assuming that one read == one packet.
    private void processIncomingFrames(byte[] bytes, int count) {
        incomingFrames.write(bytes, 0, count);
        byte[] data = incomingFrames.toByteArray();
        int offset = 0;
        while (data.length - offset >= 4) {
            int len = ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
            if (len < 2 || len > MAX_READ) {
                append("Некорректная длина USB-кадра " + len + "; буфер сброшен");
                incomingFrames.reset();
                return;
            }
            int fullLength = len + 4;
            if (data.length - offset < fullLength) break;
            byte[] frame = Arrays.copyOfRange(data, offset, offset + fullLength);
            int channel = frame[0] & 0xff;
            int msgId = ((frame[4] & 0xff) << 8) | (frame[5] & 0xff);
            if (scriptEnabled && script.isArmed()) {
                script.onFrame(frame, new ProtocolScript.Transport() {
                    @Override public void log(String line) { append(line); }
                    @Override public void send(byte[] outgoing) throws IOException {
                        sendScriptPacket(outgoing);
                    }
                });
            }
            if (Arrays.equals(frame, VERSION_REQUEST)) {
                versionRequestSeen = true;
                append("Кадр VERSION_REQUEST 1.10 подтверждён." +
                        (autoHandshake ? " Автотест отвечает 1.11." : " Нажми кнопку 3."));
                if (autoHandshake) respondVersion();
            } else if (Arrays.equals(frame, ENCAPSULATED_SSL)) {
                if (versionReplySent) {
                    encapsulatedSslSeen = true;
                    append("Кадр ENCAPSULATED_SSL (ID 3, 00 00) подтверждён." +
                            (autoHandshake ? " Автотест отправит AUTH_RESPONSE." : " Нажми кнопку 4."));
                    if (autoHandshake) respondAuth();
                } else {
                    append("Сообщение ID 3 пришло до ответа версии; AUTH_RESPONSE заблокирован");
                }
            } else if (channel == 0 && msgId == 4) {
                if (authReplySent && Arrays.equals(frame, AUTH_RESPONSE)) {
                    authCompleteSeen = true;
                    append("AUTH_COMPLETE (ID 4, status=0) получен. Штатный AutoLink теперь отправляет SERVICE_DISCOVERY_REQUEST; можно нажать кнопку 5.");
                } else {
                    append("Неожиданный ID 4: " + hex(frame, frame.length) + ". Кнопка 5 заблокирована.");
                }
            } else if (channel == 0 && msgId == 6) {
                append("SERVICE_DISCOVERY_RESPONSE получен (ID 6)! Скопируй полный RX и пришли мне, другие сообщения пока не отправляем.");
            } else {
                append("Разобран кадр: канал=" + channel + ", флаги=" +
                        String.format(Locale.US, "0x%02X", frame[1] & 0xff)
                        + ", ID=" + msgId + ", payload=" + len + " байт");
            }
            offset += fullLength;
        }
        incomingFrames.reset();
        incomingFrames.write(data, offset, data.length - offset);
        if (incomingFrames.size() > MAX_READ) {
            append("Превышен лимит накопленных данных, сброс");
            incomingFrames.reset();
        }
    }

    private void saveScript() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("usb_script", scriptEditor.getText().toString()).apply();
    }

    private void runScript() {
        try {
            String code = scriptEditor.getText().toString();
            script.loadAndArm(code);
            saveScript();
            scriptEnabled = true;
            autoHandshake = false; // Prevent duplicate automatic VERSION/AUTH replies.
            append("Сценарий загружен: " + script.stepCount() + " шагов. " +
                    "Ждём USB-подключение и точные входящие кадры. Неизвестные кадры не вызывают TX.");
            if (!listening) {
                start();
            } else if (totalRead != 0) {
                append("ВНИМАНИЕ: обмен уже начался. Для сценария с первого пакета " +
                        "вытащи USB-кабель на 5 секунд и подключи заново.");
            }
        } catch (IllegalArgumentException ex) {
            append("Ошибка сценария: " + ex.getMessage());
        }
    }

    private void stopScript() {
        script.stop();
        scriptEnabled = false;
        append("Сценарий остановлен: никакие дальнейшие пакеты по нему не отправятся.");
    }

    private void sendScriptPacket(byte[] packet) throws IOException {
        synchronized (this) {
            if (!listening || descriptor == null)
                throw new IOException("Порт отключён");
            // Runs in the RX thread; keeps each scripted send in strict RX order.
            FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
            output.write(packet);
            output.flush();
            int id = ((packet[4] & 0xff) << 8) | (packet[5] & 0xff);
            if (id == 2) versionReplySent = true;
            if (id == 4) authReplySent = true;
        }
    }

    private void enableAutoTest() {
        stopScript();
        autoHandshake = true;
        append("Автотест разрешён: отправляем максимум 1 VERSION_RESPONSE и 1 AUTH_RESPONSE, " +
                "только после ТОЧНОГО совпадения входящих запросов. Неизвестным пакетам не отвечаем.");
        if (!listening) {
            start();
        } else if (versionRequestSeen && !versionReplySent) {
            respondVersion();
        } else if (encapsulatedSslSeen && !authReplySent) {
            respondAuth();
        } else if (totalRead == 0) {
            append("USB уже открыт, но первые байты не пришли. " +
                    "Вытащи кабель на 5 секунд и подключи заново — на входящем ATTACHED откроем порт автоматически.");
        }
    }

    // Reconstructed from AutoLink 2.0.59 libaljni.so:
    // Controller::sendServiceDiscoveryRequest at 0x4ffa0 uses proto message ID 5.
    // ServiceDiscoveryRequest::SerializeWithCachedSizes at 0x5d2b8 confirms:
    // fields 1..4 = string, fields 5..6 = uint32. JNI nativeSetPhoneInfo
    // confirms their order: manufacturer, model, Android version, device name,
    // screen width, screen height. No identifiers/serials are transmitted.
    private void sendServiceDiscovery() {
        if (!listening || descriptor == null || !authCompleteSeen || !authReplySent) {
            append("SERVICE_DISCOVERY заблокирован: сначала пройди VERSION / AUTH и получи подтверждённый AUTH_COMPLETE.");
            return;
        }
        if (discoveryRequestSent) {
            append("SERVICE_DISCOVERY_REQUEST уже отправлен в этой USB-сессии.");
            return;
        }

        DisplayMetrics display = getResources().getDisplayMetrics();
        final int width = Math.max(1, display.widthPixels);
        final int height = Math.max(1, display.heightPixels);
        final String manufacturer = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER;
        final String model = Build.MODEL == null ? "Phone" : Build.MODEL;
        final String version = Build.VERSION.RELEASE == null ? "16" : Build.VERSION.RELEASE;
        // Build.DEVICE is a generic product code, not a user's personal phone name.
        final String name = Build.DEVICE == null ? "android" : Build.DEVICE;
        final byte[] frame;
        try {
            ByteArrayOutputStream proto = new ByteArrayOutputStream();
            putString(proto, 1, manufacturer);
            putString(proto, 2, model);
            putString(proto, 3, version);
            putString(proto, 4, name);
            putVarint(proto, 5 << 3);
            putVarint(proto, width);
            putVarint(proto, 6 << 3);
            putVarint(proto, height);

            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(0);
            payload.write(5);
            byte[] protoBytes = proto.toByteArray();
            payload.write(protoBytes, 0, protoBytes.length);
            byte[] fullPayload = payload.toByteArray();
            if (fullPayload.length > 4096) throw new IOException("Discovery payload too large");

            ByteArrayOutputStream wire = new ByteArrayOutputStream();
            wire.write(0); // control channel
            wire.write(3); // unencrypted, final frame
            wire.write((fullPayload.length >> 8) & 0xff);
            wire.write(fullPayload.length & 0xff);
            wire.write(fullPayload, 0, fullPayload.length);
            frame = wire.toByteArray();
        } catch (Exception ex) {
            append("Не удалось сформировать ServiceDiscoveryRequest: " + ex);
            return;
        }

        discoveryRequestSent = true;
        final ParcelFileDescriptor opened = descriptor;
        new Thread(() -> {
            try {
                synchronized (MainActivity.this) {
                    if (!listening || descriptor != opened) {
                        discoveryRequestSent = false;
                        append("USB-сессия закрыта, SERVICE_DISCOVERY не отправлен.");
                        return;
                    }
                    FileOutputStream output = new FileOutputStream(opened.getFileDescriptor());
                    output.write(frame);
                    output.flush();
                }
                append("TX SERVICE_DISCOVERY_REQUEST ID=5, manufacturer=" + manufacturer
                        + ", model=" + model + ", Android=" + version + ", device=" + name
                        + ", resolution=" + width + "x" + height + ": "
                        + hex(frame, frame.length));
                append("Ожидаю SERVICE_DISCOVERY_RESPONSE (ID 6). Никаких последующих команд без нового исследования.");
            } catch (Exception ex) {
                discoveryRequestSent = false;
                append("Ошибка SERVICE_DISCOVERY_REQUEST: " + ex);
            }
        }, "aoa-service-discovery").start();
    }

    private static void putString(ByteArrayOutputStream out, int field, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 255) throw new IOException("Phone info field exceeds 255 bytes");
        putVarint(out, (field << 3) | 2);
        putVarint(out, bytes.length);
        out.write(bytes);
    }

    private static void putVarint(ByteArrayOutputStream out, int value) {
        long unsignedValue = value & 0xffffffffL;
        while ((unsignedValue & ~0x7fL) != 0) {
            out.write(((int) unsignedValue & 0x7f) | 0x80);
            unsignedValue >>>= 7;
        }
        out.write((int) unsignedValue);
    }

    private void respondAuth() {
        if (!listening || descriptor == null || !versionReplySent) {
            append("AUTH_RESPONSE заблокирован: сначала подключись и ответь на VERSION_REQUEST.");
            return;
        }
        if (!encapsulatedSslSeen) {
            append("AUTH_RESPONSE заблокирован: не получен точный кадр ENCAPSULATED_SSL 00 00.");
            return;
        }
        if (scriptEnabled && script.isArmed()) {
            append("Ручной ответ недоступен пока выполняется сценарий."); return;
        }
        if (authReplySent) {
            append("AUTH_RESPONSE уже отправлялся в этой сессии.");
            return;
        }
        authReplySent = true;
        final ParcelFileDescriptor opened = descriptor;
        new Thread(() -> {
            try {
                synchronized (MainActivity.this) {
                    if (!listening || descriptor != opened) {
                        authReplySent = false;
                        append("Соединение закрыто, AUTH_RESPONSE не отправлен");
                        return;
                    }
                    // Stream shares the descriptor with the receive thread; do not close it.
                    FileOutputStream output = new FileOutputStream(opened.getFileDescriptor());
                    output.write(AUTH_RESPONSE);
                    output.flush();
                }
                append("TX AUTH_RESPONSE status=0: " + hex(AUTH_RESPONSE, AUTH_RESPONSE.length));
                append("Ждём ответ магнитолы. Дальнейшие сообщения автоматически не отправляются.");
            } catch (Exception ex) {
                authReplySent = false;
                append("Ошибка AUTH_RESPONSE: " + ex.getMessage());
            }
        }, "aoa-auth-response").start();
    }

    private void respondVersion() {
        if (!listening || descriptor == null) {
            append("Ответ невозможен: сначала открой USB на шаге 2.");
            return;
        }
        if (!versionRequestSeen) {
            append("Ответ заблокирован: точный VERSION_REQUEST v1.10 ещё не распознан.");
            return;
        }
        if (scriptEnabled && script.isArmed()) {
            append("Ручной ответ недоступен пока выполняется сценарий."); return;
        }
        if (versionReplySent) {
            append("VERSION_RESPONSE уже отправлялся в этой сессии.");
            return;
        }
        versionReplySent = true;
        final ParcelFileDescriptor opened = descriptor;
        new Thread(() -> {
            try {
                synchronized (MainActivity.this) {
                    if (!listening || descriptor != opened) {
                        versionReplySent = false;
                        append("Сессия USB уже закрыта; ответ не отправлен.");
                        return;
                    }
                    // Do not close this stream: closing the shared descriptor would
                    // also terminate the simultaneous RX stream.
                    FileOutputStream output = new FileOutputStream(opened.getFileDescriptor());
                    output.write(VERSION_RESPONSE);
                    output.flush();
                }
                append("TX VERSION_RESPONSE 1.11 (MATCH): " +
                        hex(VERSION_RESPONSE, VERSION_RESPONSE.length));
                append("Ждём следующий входящий пакет. TLS и сервисы пока не реализованы.");
            } catch (Exception ex) {
                versionReplySent = false;
                append("Ошибка отправки VERSION_RESPONSE: " + ex.getMessage());
            }
        }, "aoa-version-response").start();
    }

    private String hex(byte[] bytes, int count) {
        StringBuilder result = new StringBuilder(count * 3);
        for (int i = 0; i < count; i++) {
            result.append(String.format(Locale.US, "%02X ", bytes[i] & 0xff));
        }
        return result.toString();
    }

    private synchronized void closeDescriptor() {
        if (descriptor != null) {
            try { descriptor.close(); } catch (IOException ignored) {}
            descriptor = null;
        }
    }

    private synchronized void stopReading() {
        sessionId++;
        listening = false;
        closeDescriptor();
    }

    private void exportLog() {
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        save.addCategory(Intent.CATEGORY_OPENABLE);
        save.setType("text/plain");
        save.putExtra(Intent.EXTRA_TITLE, "autolink-usb-log.txt");
        startActivityForResult(save, CREATE_LOG);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_LOG && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
                if (output == null) throw new IOException("Не удалось открыть файл");
                String snapshot;
                synchronized (log) { snapshot = log.toString(); }
                output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                append("Лог сохранён");
            } catch (Exception ex) {
                append("Ошибка экспорта: " + ex.getMessage());
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        append("Новый intent: " + intent.getAction());
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction()) && listening) {
            // Android can deliver a second ATTACHED launch intent for the same
            // accessory. Never discard an active streaming session solely
            // because the activity received another launch intent: the device
            // did not necessarily detach or physically re-enumerate.
            append("Повторный USB ATTACHED при активном RX: сохраняю открытую сессию "
                    + sessionId + ", не закрываю порт.");
            return;
        }
        scan();
        if (selected != null && UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
            append("AOA ATTACHED — автоматически открываю USB.");
            start();
        }
    }

    @Override protected void onDestroy() {
        stopReading();
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
