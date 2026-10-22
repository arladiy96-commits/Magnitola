package com.example.autolinkprobe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Minimal user-armed, receive-driven USB protocol script.
 *
 * Grammar:
 *   EXPECT <full hex frame; optionally ?? for one wildcard byte>
 *   PREFIX <hex prefix; optionally ?? for one wildcard byte>
 *   SEND <full hex frame>
 *   DONE
 * No loops, delays, unsolicited writes or conditionally generated frames.
 */
final class ProtocolScript {
    interface Transport {
        void send(byte[] frame) throws IOException;
        void log(String line);
    }

    private static final class Step {
        final String action;
        final byte[] bytes;
        final boolean[] wildcards;

        Step(String action, byte[] bytes, boolean[] wildcards) {
            this.action = action;
            this.bytes = bytes;
            this.wildcards = wildcards;
        }
    }

    static final String DEFAULT_SCRIPT =
            "# Магнитола GAZ EW6800-FU — подтвержденные этапы\n" +
            "# Запусти сценарий ДО подключения USB\n" +
            "EXPECT 00 03 00 06 00 01 00 01 00 0A\n" +
            "SEND 00 03 00 08 00 02 00 01 00 0B 00 00\n" +
            "EXPECT 00 03 00 04 00 03 00 00\n" +
            "SEND 00 03 00 04 00 04 08 00\n" +
            "EXPECT 00 03 00 04 00 04 08 00\n" +
            "DONE\n";

    private final List<Step> steps = new ArrayList<>();
    private boolean armed = false;
    private int position = 0;

    synchronized boolean isArmed() { return armed; }

    synchronized int stepCount() { return steps.size(); }

    synchronized void loadAndArm(String source) {
        List<Step> parsed = parse(source);
        steps.clear();
        steps.addAll(parsed);
        position = 0;
        armed = true;
    }

    synchronized void stop() {
        armed = false;
        position = 0;
    }

    synchronized void onReconnect() {
        if (armed) position = 0;
    }

    synchronized void onFrame(byte[] incoming, Transport transport) {
        if (!armed || position >= steps.size()) return;
        Step expected = steps.get(position);
        if (!"EXPECT".equals(expected.action) && !"PREFIX".equals(expected.action)) {
            transport.log("Сценарий остановлен: ожидался EXPECT/PREFIX на позиции " + (position + 1));
            armed = false;
            return;
        }
        if (!matches(expected, incoming)) {
            transport.log("Сценарий: неожиданный кадр; жду шаг " + (position + 1) +
                    " (" + expected.action + ")");
            return;
        }
        transport.log("Сценарий: совпадение шага " + (position + 1) + "/" + steps.size());
        position++;
        while (armed && position < steps.size()) {
            Step next = steps.get(position);
            if ("SEND".equals(next.action)) {
                try {
                    transport.send(Arrays.copyOf(next.bytes, next.bytes.length));
                    transport.log("Сценарий TX: " + toHex(next.bytes));
                    position++;
                } catch (IOException ex) {
                    transport.log("Сценарий TX ОШИБКА: " + ex.getMessage());
                    armed = false;
                    return;
                }
            } else if ("DONE".equals(next.action)) {
                armed = false;
                position++;
                transport.log("СЦЕНАРИЙ ВЫПОЛНЕН: " + position + " шагов. Ожидаем дальнейший RX для анализа.");
            } else {
                transport.log("Сценарий ожидает " + next.action + " (" + (position + 1) + "/" +
                        steps.size() + ")");
                return;
            }
        }
    }

    private static boolean matches(Step step, byte[] bytes) {
        if ("EXPECT".equals(step.action) && bytes.length != step.bytes.length) return false;
        if ("PREFIX".equals(step.action) && bytes.length < step.bytes.length) return false;
        for (int i = 0; i < step.bytes.length; i++) {
            if (!step.wildcards[i] && step.bytes[i] != bytes[i]) return false;
        }
        return true;
    }

    private static List<Step> parse(String source) {
        List<Step> result = new ArrayList<>();
        String[] lines = source.replace("\r", "").split("\n");
        if (lines.length > 80) throw new IllegalArgumentException("Максимум 80 строк сценария");
        boolean waiting = false;
        int sends = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].split("#", 2)[0].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            String action = parts[0].toUpperCase(Locale.US);
            if (!("EXPECT".equals(action) || "PREFIX".equals(action)
                    || "SEND".equals(action) || "DONE".equals(action))) {
                throw new IllegalArgumentException("Строка " + (i + 1) + ": неизвестная команда " + action);
            }
            if ("DONE".equals(action)) {
                if (parts.length != 1) throw new IllegalArgumentException("DONE не принимает аргументы");
                if (!waiting) throw new IllegalArgumentException("DONE без предшествующего EXPECT");
                result.add(new Step(action, new byte[0], new boolean[0]));
                if (i < lines.length - 1) {
                    for (int n = i + 1; n < lines.length; n++) {
                        if (!lines[n].split("#", 2)[0].trim().isEmpty()) {
                            throw new IllegalArgumentException("Команды после DONE запрещены");
                        }
                    }
                }
                break;
            }
            if (parts.length < 2) throw new IllegalArgumentException("Строка " + (i + 1) + ": нужны hex-байты");
            if (parts.length - 1 > 260) throw new IllegalArgumentException("Лимит 260 байт на строку");
            byte[] bytes = new byte[parts.length - 1];
            boolean[] wc = new boolean[bytes.length];
            for (int j = 1; j < parts.length; j++) {
                String token = parts[j];
                if ("??".equals(token) && !"SEND".equals(action)) {
                    wc[j - 1] = true;
                } else if (token.matches("[0-9a-fA-F]{2}")) {
                    bytes[j - 1] = (byte) Integer.parseInt(token, 16);
                } else {
                    throw new IllegalArgumentException("Строка " + (i + 1) + ": неверный байт " + token);
                }
            }
            if ("SEND".equals(action)) {
                if (!waiting) throw new IllegalArgumentException("SEND разрешён только после EXPECT/PREFIX");
                sends++;
                if (sends > 12) throw new IllegalArgumentException("Максимум 12 отправок за сессию");
                if (bytes.length < 6 || bytes[0] != 0 || bytes[1] != 3) {
                    throw new IllegalArgumentException("SEND: только простые кадры канала 0 с флагами 03");
                }
                int len = ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
                if (len != bytes.length - 4) {
                    throw new IllegalArgumentException("SEND: длина в заголовке не совпадает с данными");
                }
                waiting = false;
            } else {
                if (bytes.length < 6) throw new IllegalArgumentException(action + ": минимум 6 байт");
                if (waiting) throw new IllegalArgumentException("Два EXPECT/PREFIX подряд пока не поддерживаются");
                waiting = true;
            }
            result.add(new Step(action, bytes, wc));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Пустой сценарий");
        if (!"DONE".equals(result.get(result.size() - 1).action)) {
            throw new IllegalArgumentException("Последней строкой должен быть DONE");
        }
        return result;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02X ", b & 0xff));
        return sb.toString().trim();
    }
}
