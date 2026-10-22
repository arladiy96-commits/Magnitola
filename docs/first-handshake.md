# Первый подтверждённый USB-пакет GAZ EW6800-FU — 22.10.2026

## Реальные наблюдения на POCO, Android 16 (SDK 36)

AOA manufacturer: Sunplus, Inc.
AOA model: DVD
AOA version: 2.0
USB permission: true

Магнитола два раза при разных подключениях прислала одну и ту же последовательность:

```hex
00 03 00 06 00 01 00 01 00 0A
```

После этого без ответа со стороны телефона больше данных не было. В прежнем тесте EIO случилась после отключения AOA-устройства, а не при первом приёме.

## Разбор по структуре Android Auto (GAL)

| Hex | Поле | Значение |
| --- | --- | --- |
| 00 | channel_id | control channel 0 |
| 03 | flags | plaintext, bulk/single-frame |
| 00 06 | length | 6 bytes |
| 00 01 | message id | VERSION_REQUEST |
| 00 01 | major | 1 |
| 00 0A | minor | 10 |

Сравнение с форматом от aasdk / open-android-auto:
- https://github.com/mrmees/open-android-auto/blob/main/docs/channels/architecture.md
- https://github.com/mrmees/open-android-auto/blob/main/docs/interactions/02-version-ssl-auth.md

## Исследование оригинального APK AutoLink 2.0.59

ARM64 libaljni.so содержит функцию с сохранённым именем `Controller::handleVersionRequest(void*, unsigned long)` по адресу 0x4f824.

По ARM64 дизассемблированию:
- Читает два big-endian unsigned short (major и minor) из входного payload, уже без message id.
- При major == 1 создаёт 8-байтный ответ с payload `00 02 00 01 00 0B 00 00`.
- При несовместимой основной версии выставляет байты статуса в `FF FF`.
- Отправляет ответ через `MessageRouter::queueOutgoingUnencrypted`.

Данные ответа восстановлены из `.rodata` по адресу 0x286a0 (`00 02 00 01`) и ARM64-инструкций `mov w9, #0xb00; strh w9, [x0, #4]` (little endian `00 0B`) и `strb w21, [x0, #6/#7]` (status MATCH: `00 00`).

Полный предполагаемый исходящий USB-кадр с AA framing:

```hex
00 03 00 08 00 02 00 01 00 0B 00 00
```

**Важно:** 8 байт payload подтверждены по оригинальному коду. Четырёхбайтный AA framing построен по расшифрованному входящему кадру; тест на железе ещё не выполнялся.

## Новый безопасный эксперимент

Probe 0.2: новое действие «Ответить VERSION_RESPONSE 1.11 (один раз)»:
1. Подключить магнитолу и открыть порт через шаг 2.
2. Убедиться, что приложение распознало точные 10 байт `VERSION_REQUEST`.
3. Нажать кнопку версии только после явного решения пользователя. Без совпадения байт отправка блокируется.
4. Скопировать/экспортировать лог, включая TX и последующий RX.
5. Дальше возможен TLS ClientHello/ServerHello или иной контрольный пакет. TLS и последующие сообщения приложение пока не обрабатывает. Никаких дополнительных команд не отправляет.

Цель: подтвердить правильность VERSION_RESPONSE и получить следующий этап рукопожатия. Не обещает видеовывода.
