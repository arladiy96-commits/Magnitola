# Native AutoLink video protocol — 22 October 2026

The field log at 12:42:18 local shows normal Version/Auth/ServiceDiscovery, channel=1 opened with status=0, but sending standard AA SETUP ID 0x8000 on channel 1 received `01 0B 00 02 00 FF` (ID 0x00FF). The preceding run with the same SETUP ID but flag 0x03 got `00 03 00 02 FF 05`. After the native error, PingRequest ID11 was still serviced eight times, so the USB link had not failed immediately.

Root cause found in the **verified original vendor APK**:
- Public original AutoLink 2.0.59 (`com.link.autolink`) SHA-256 `2c883dc835799fa11aa58964ccde9fd57bd1b6224bb155a21e90ce04fde1dbfb` matches earlier analysis.
- ARM64 `libaljni.so` `MediaSourceBase::sendSetup` (vaddr 0x55264) calls `MessageRouter::marshallProto(2,...)`, **not** Android Auto 0x8000. Setup protobuf field1 is codec (H264=3).
- `MediaSourceBase::sendStart` (0x55364) calls marshallProto ID **3** (not 0x8001). Source Java `u1/m.java` calls `VideoSource.sendStart(session, configIndex, width, height)`. Native protobuf storage order is field1=config index; field2=session; field3=width; field4=height.
- `MediaSourceBase::sendStop` (0x55480) emits message ID **4**.
- `MediaSourceBase::sendCodecConfig` (0x554e8) emits message ID **1**, followed by H.264 SPS/PPS bytes.
- `MediaSourceBase::sendData` (0x5556c) emits message ID **0**, then an 8-byte big-endian timestamp and encoded data.
- `MediaSourceBase::routeMessage` (0x54f40) dispatches **Config=5, Ack=6, StartResponse=7, StopResponse=8**, and checks 0x00FF. These are the native channel-1 reply IDs.
- `VideoSource::routeMessage` (0x55f0c) additionally routes native video messages **0x1001** (focus indication) and **0x1003** (display area change). `VideoSource::sendVideoFocusRequest` (0x56658) marshals **0x1000**; the Java class has a 3-int native method.
- `VideoSource::handleConfig` (0x56248) passes two scalar fields and a repeated config-index list to the Java callback. When transport mode field is 5, native code initializes a separate RTP session; therefore USB payload transmission must not be blindly used in that case.
- `ChannelManager::messageToFrames` (0x531cc) uses a maximum split payload length of 0x3f00 bytes. The queueOutgoing path sets the encrypted flag bit, although current captured vendor control protobuf has remained directly parseable; actual crypto remains unverified.

**Fixes introduced in app v0.3:** control channel opening stays on the proven wire channel 1 with ID7; after channel OPEN ID8 response, native SETUP ID2 replaces AA 0x8000. Native CONFIG ID5 is parsed, and when ready, native START ID3 includes configIndex/session/1280x720. Only on successful START_RESPONSE ID7 does the Android MediaCodec encoder start and send native codec config ID1 and frames ID0. Native ACK ID6 provides flow control; focus messages use native IDs 0x1000/0x1001; all control TX is hex-logged. The transportMode=5 guard prevents sending AOA H264 when the HU selected RTP.

**Still needs a physical run:** Config values and status, StartResponse, whether the HU genuinely accepts clear protobuf marked with frame flag 0x0B, and whether native focus requests require specific enum values. A successful compile does not establish that the screen shows video.

Native ARM64 analysis was performed on an ephemeral GitHub Actions runner against a SHA-verified APK. The proprietary APK and SO were not committed or included in the diagnostic text artifacts.
