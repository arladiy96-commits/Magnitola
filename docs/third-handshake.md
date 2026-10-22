# Третий эксперимент: AUTH_COMPLETE принят, подготовлен SERVICE_DISCOVERY_REQUEST

Дата полевого теста: 2026-10-22, 10:56 (Asia/Almaty). Устройство: POCO Android 16 SDK 36, AOA Sunplus Inc. DVD v2.0.

## Полный подтверждённый переход

```hex
RX 00 03 00 06 00 01 00 01 00 0A
TX 00 03 00 08 00 02 00 01 00 0B 00 00
RX 00 03 00 04 00 03 00 00
TX 00 03 00 04 00 04 08 00
RX 00 03 00 04 00 04 08 00
```

Последний RX = channel0 / flags=3 / length=4 / ID=4 / protobuf field 1 (status)=0. Таблица ветвления `Controller::routeMessage()` исходного ARM64 `libaljni.so` по адресу 0x4f064, jump table в 0x28652, для ID4 адрес 0x4f334. Ветка вызывает callback (0x4f358) и затем `Controller::sendServiceDiscoveryRequest()` (0x4f360). В связи с этим обозначение **AUTH_COMPLETE** в данном протоколе подтверждено кодом, хотя общее завершение всех стадий подключения и готовность видеотракта ещё не подтверждены.

## Схема исходящего SERVICE_DISCOVERY_REQUEST

Дизассемблирование `Controller::sendServiceDiscoveryRequest()` по 0x4ffa0, `autolink::ServiceDiscoveryRequest::SerializeWithCachedSizes` по 0x5d2b8 и JNI `GalReceiver_nativeSetPhoneInfo` по 0x4856c показывает:

| Поле protobuf | Wire type | Значение |
| --- | --- | --- |
| 1 | bytes/string | Android MANUFACTURER |
| 2 | bytes/string | Android MODEL |
| 3 | bytes/string | Android RELEASE |
| 4 | bytes/string | Android DEVICE (не персонализированное имя) |
| 5 | uint32 varint | текущая ширина в пикселях |
| 6 | uint32 varint | текущая высота в пикселях |

В исходном JNI названия Java-полей `manufacturer`, `model`, `version`, `name`, `width`, `height` сопоставлены со смещениями объекта контроллера 0x40, 0x58, 0x70, 0x88, 0xA0, 0xA4 соответственно. `sendServiceDiscoveryRequest` записывает их в поля 1–6 и вызывает `MessageRouter::marshallProto(5,...)`.

Wire framing: control channel=0, unencrypted flag=0x03, length=(2+serialized protobuf bytes) big-endian, message ID=0x0005, затем protobuf.

## Безопасный тест

Кнопка 5 доступна после подтверждённого AUTH_COMPLETE. Ни номера телефона, ни идентификатора устройства/IMEI/серийника не отправляется. В точности как оригинал используются стандартные сведения о модели и размере экрана. Значения действительно отправленного пакета записываются в лог, чтобы можно было проверить их без догадок.

**После следующего реального теста** ожидаем RX ID6 `ServiceDiscoveryResponse`, а не гарантию готового экрана. Ответ может содержать список сервисов, channels, форматы и видеопараметры. Пока любые дальнейшие команды после ID5 запрещены. Если ответ не придёт, сохраняем весь лог и исследуем поля/дополнительные условия.
