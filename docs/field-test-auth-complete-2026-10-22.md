# Field test 2026-10-22 10:56 local

Android 16 SDK36; AOA Sunplus, Inc. DVD v2.0.

Captured chronological packets:
- RX 00 03 00 06 00 01 00 01 00 0A (version request 1.10)
- TX 00 03 00 08 00 02 00 01 00 0B 00 00 (version response 1.11)
- RX 00 03 00 04 00 03 00 00 (ID3)
- TX 00 03 00 04 00 04 08 00 (auth response status=0)
- RX 00 03 00 04 00 04 08 00 (ID4; possible auth complete status=0)

No additional TX. The matching ID4 packet alone does not establish successful authentication; next step is checking native protocol handling and constructing a justified service discovery request. Do not blindly transmit arbitrary messages. Auto-open USB on attach worked. Autotest was enabled after version request and continued handshake correctly.
