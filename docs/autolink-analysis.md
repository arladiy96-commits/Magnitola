# Технические ориентиры AutoLink 2.0.59

Источник: локальный статический анализ переданного владельцем APK com.link.autolink.apk.
SHA-256 файла: 2c883dc835799fa11aa58964ccde9fd57bd1b6224bb155a21e90ce04fde1dbfb.

## Подтверждённые признаки APK

- Пакет com.link.autolink, версия 2.0.59 (versionCode 59).
- USB-фильтр приложения: производитель Sunplus, Inc.; модель DVD; версии 1.0 и 2.0.
- Поддержка Android Open Accessory; intent USB_ACCESSORY_ATTACHED.
- Нативная библиотека libaljni.so присутствует для ARM64 и ARMv7.
- В таблице символов нативной библиотеки обнаружены JNI-методы GalReceiver.nativeInit, nativeQueueIncoming, nativeGetEncodedFrame, nativeSendScreenResolutionNotification, VideoSource.sendData/sendStart/sendStop, InputSink.sendStart.
- Среди названий компонентов — Controller, ChannelManager, MessageRouter, VideoSource, InputSinkCallbacks, RTPSender, NetworkSession и BluetoothEndpoint.
- Среди обозначений протокола встречаются VIDEO_TRANSPORT_MODE_AOA, TCP, UDP, RTP_OVER_TCP, RTP_OVER_UDP, PACKETIZATION_H264, SERVICE_DISCOVERY, VERSION, PingRequest/Response и события ввода.

## Что пока НЕ подтверждено

- Как именно выполняется USB-рукопожатие с нашей магнитолой.
- Выбирается ли транспорт AOA для видеопотока или в этой конфигурации нужен иной канал.
- Реальные номера protobuf-полей, значения enum, структура фрейминга и назначение каналов.
- Возможность вывода собственного экрана при блокировке телефона на Android 16.
- Факт работы собственного приложения вместо штатного AutoLink.

## Проверяемая гипотеза

POCO → отдельное UI 800×480 → аппаратное кодирование H.264 (MediaCodec) → совместимый видеоканал AutoLink → магнитола; обратно касания через InputSink.

## Последовательность

1. Собрать и проверить USB Probe, получить AOA-метаданные и лог.
2. Восстановить рукопожатие по анализу программной части и собственным логам.
3. Отправить тестовый кадр, а затем получить одно событие касания.
4. После этого проектировать автомобильное UI и независимый виртуальный дисплей.

Никакие бинарные библиотеки и код из закрытого APK в этот репозиторий не добавлены. Это только результаты наблюдений; утверждений о готовой совместимости нет.
