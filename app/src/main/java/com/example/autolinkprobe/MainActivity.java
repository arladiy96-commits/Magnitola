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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
 * An intentionally receive-only Android Open Accessory monitor.
 * Never transmits packets to the attached head unit.
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
    private int totalRead;
    private volatile boolean versionRequestSeen;
    private volatile boolean versionReplySent;
    private volatile boolean encapsulatedSslSeen;
    private volatile boolean authReplySent;
    private final ByteArrayOutputStream incomingFrames = new ByteArrayOutputStream();
    private TextView logView;
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
        root.addView(label("Только чтение USB. Не изменяет прошивку магнитолы и ничего ей не отправляет."));
        addButton(root, "1. Найти USB-аксессуар", this::scan);
        addButton(root, "2. Слушать USB (не больше 64 КБ)", this::start);
        addButton(root, "3. Ответить VERSION_RESPONSE 1.11 (один раз)", this::respondVersion);
        addButton(root, "4. Ответить AUTH_RESPONSE (status=0)", this::respondAuth);
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

    private void openAccessory() {
        if (selected == null || listening) return;
        try {
            descriptor = usb.openAccessory(selected);
            if (descriptor == null) {
                append("openAccessory вернул null: нет разрешения или порт занят");
                return;
            }
            final ParcelFileDescriptor opened = descriptor;
            listening = true;
            totalRead = 0;
            versionRequestSeen = false;
            versionReplySent = false;
            encapsulatedSslSeen = false;
            authReplySent = false;
            incomingFrames.reset();
            append("Порт открыт. Только входящий поток; никаких команд и handshake.");
            new Thread(() -> {
                try (FileInputStream input = new FileInputStream(opened.getFileDescriptor())) {
                    byte[] buffer = new byte[1024];

                    int count;
                    while (listening && totalRead < MAX_READ
                            && (count = input.read(buffer)) != -1) {
                        totalRead += count;
                        processIncomingFrames(buffer, count);
                        int hexLength = Math.min(count, 256);
                        append("RX " + count + " байт: " + hex(buffer, hexLength)
                                + (count > hexLength ? " [остальное опущено]" : ""));
                    }
                } catch (IOException ex) {
                    if (listening) append("Ошибка чтения: " + ex.getMessage());
                } finally {
                    listening = false;
                    append("Чтение завершено; принято " + totalRead + " байт");
                    closeDescriptor();
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
            if (Arrays.equals(frame, VERSION_REQUEST)) {
                versionRequestSeen = true;
                append("Кадр VERSION_REQUEST 1.10 подтверждён. Нажми кнопку 3.");
            } else if (Arrays.equals(frame, ENCAPSULATED_SSL)) {
                if (versionReplySent) {
                    encapsulatedSslSeen = true;
                    append("Кадр ENCAPSULATED_SSL (ID 3, 00 00) подтверждён. Оригинальный AutoLink отвечает AUTH_RESPONSE(status=0). Нажми кнопку 4.");
                } else {
                    append("Сообщение ID 3 пришло до ответа версии; AUTH_RESPONSE заблокирован");
                }
            } else if (channel == 0 && msgId == 4) {
                append("Кадр ID=4 (вероятно AUTH_COMPLETE): " + hex(frame, frame.length)
                        + ". Стоковый AutoLink после него отправляет SERVICE_DISCOVERY_REQUEST (ID 5). Пока не отправляем.");
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

    private void respondAuth() {
        if (!listening || descriptor == null || !versionReplySent) {
            append("AUTH_RESPONSE заблокирован: сначала подключись и ответь на VERSION_REQUEST.");
            return;
        }
        if (!encapsulatedSslSeen) {
            append("AUTH_RESPONSE заблокирован: не получен точный кадр ENCAPSULATED_SSL 00 00.");
            return;
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

    private void stopReading() {
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
        scan();
    }

    @Override protected void onDestroy() {
        stopReading();
        unregisterReceiver(receiver);
        super.onDestroy();
    }
}
