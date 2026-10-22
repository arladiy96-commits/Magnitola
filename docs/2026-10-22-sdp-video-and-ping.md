# 22 октября 2026: Service Discovery + Ping из реальной магнитолы

## Подтверждённая сессия 11:48 (POCO Android 16, GAZ EW6800-FU)

Сценарий из восьми шагов автоматически выполнил:
1. RX `00 03 00 06 00 01 00 01 00 0A` — VERSION_REQUEST.
2. TX `00 03 00 08 00 02 00 01 00 0B 00 00` — VERSION_RESPONSE.
3. RX `00 03 00 04 00 03 00 00` — этап ID3.
4. TX `00 03 00 04 00 04 08 00` — AUTH_RESPONSE.
5. RX `00 03 00 04 00 04 08 00` — подтверждение ID4.
6. TX `00 03 00 26 00 05 0A 06 58 69 61 6F 6D 69 12 0A 32 36 30 32 42 50 43 31 38 47 1A 02 31 36 22 04 64 61 73 68 28 80 0A 30 D4 15` — phone ServiceDiscoveryRequest.
7. RX: **158 bytes**, header `00 0B 00 9A 00 06`: ServiceDiscoveryResponse ID6. Payload exactly 152 bytes (length field 154 includes ID=2 bytes). Script's `PREFIX 00 03 ?? ?? 00 06` fails solely because flag is `0B`, not `03`.
8. Two seconds later, duplicate `USB_ACCESSORY_ATTACHED` launch intent, without observed DETACHED event. Old client code unconditionally closed the valid reader and opened another USB session. Next RX `00 0B 00 06 00 0B 08 01 10 00` (ID11, PingRequest candidate).

### Decoding the received protobuf SDP payload

Payload top-level protobuf:
- field1 = one `ChannelDescriptor` (25 bytes)
  - descriptor field1 `channel_id=1`
  - descriptor field3 = `AVChannel`
    - AVChannel field1 `stream_type=3` (H.264 video)
    - AVChannel field4 = one `VideoConfiguration` (17 bytes):
      - field1 resolution enum 2 (1280×720 in THIS AutoLink native VideoSource::discoverService)
      - field2 fps enum 2 (30fps in THIS AutoLink native VideoSource::discoverService)
      - field3=0; field4=0; field5=160; field6=0; field8=0; field9=3.
- field2 = "Volkswagen"; field3 = "POLO"; field4 = "2019"; other labels include "headuint", "car", "CarSDK", "V1.0.0.0", "8268K", "autolink-demo".
- field5 = 32-character identifier (intentionally not copied into this public project doc).
- fields16,17,18,19 = 0.

**Note:** These values are the head-unit's *advertised compatibility profile*, not proof of the vehicle's actual make/year.

Source of code-level resolution mapping: ORIGINAL AutoLink 2.0.59 `libaljni.so`, function `VideoSource::discoverService` at offset 0x560f0: enum1→800×480, enum2→1280×720, enum3→1920×1080; second enum2 maps to 30fps. The head-unit's 800×480 physical panel does not mandate an 800×480 encoded H.264 stream.

### Flags: an open question

The incoming SDP and ping packets use flag `0x0B`. Public Android Auto references label bit3 as ENCRYPTED; however the RX bytes after message ID clearly parse as ordinary protobuf, so we should **not assume actual payload encryption**, nor assume unencrypted transmission will be accepted. Need verify framing behavior from original native ChannelManager before attempting the next transmitted packet.

### Ping ID11

Incoming `00 0B 00 06 00 0B 08 01 10 00` represents message ID11, protobuf `field1=1; field2=0`. The original native `Controller::routeMessage` at 0x4f21c constructs `autolink::PingRequest`, and `Controller::sendPingResponse(long)` at 0x50190 sends protobuf `PingResponse` message ID12 using `MessageRouter::queueOutgoing`. Response framing/encryption policy must be confirmed; DO NOT blindly send a plaintext reply.

### Genuine software bug fixed after this log

The original `onNewIntent` treated every extra `USB_ACCESSORY_ATTACHED` as a new physical connection, closed the active USB reader, and reset scripts. The absence of a DETACHED event means this is not justified. Commit `da6f8594a535f82646a13784c66a445c43dee68f` preserves the existing active session upon duplicate attach.

## Next *real* milestone

Implement and validate:
1. Correct flags and native-compatible channel open request, using SDP-assigned wire channel1 (video), not hardcoded typical Android Auto channel3.
2. Keepalive handling ID11→ID12 without breaking a valid session.
3. Video channel setup/Start exchange, then H.264 test card at advertised 1280×720/30fps, scaled by the HU to its display.
4. Only after a real video frame, independent navigation UI and touch input.

No request for user to reinstall APK merely to confirm already-captured ServiceDiscoveryResponse. Combine these into a meaningful video-capable build instead.
