# Field test: AOA opened but no RX (22 Oct 2026 10:38–10:39 local)

Observed on Android 16 / SDK 36:

```
10:38:49.010 intent USB_ACCESSORY_ATTACHED
10:38:49.012 AOA Sunplus, Inc. / DVD, version=2.0, permission=true
10:38:53.979 port opened
10:38:58.185 VERSION_RESPONSE blocked: exact VERSION_REQUEST not seen
10:39:02.517 AUTH_RESPONSE blocked: no version response
10:39:12.062 already listening
10:39:31.013 VERSION_RESPONSE blocked: exact VERSION_REQUEST not seen
```

**Most important:** not a failed VERSION_RESPONSE or failed AUTH_RESPONSE. No `RX` at all; reader blocked waiting for USB data. Prior captures on the same hardware proved `00 03 00 06 00 01 00 01 00 0A` can be received, and version response `00 03 00 08 00 02 00 01 00 0B 00 00` triggered `00 03 00 04 00 03 00 00`.

Possible explanations not yet proven: a timing race during attach/start, accessory sent its greeting before probe opened, another client or head-unit state interfering, or the head unit did not transmit for this session.

Fix in subsequent code:
- automatic port opening on USB_ACCESSORY_ATTACHED;
- raw read logging before parser;
- 7s and 20s diagnostics for silent read;
- generation guard to prevent the old USB read thread from terminating a newly attached session;
- explicit opt-in auto-replies for exactly the two known frames (one each per session);
- physical unplug/replug test recommended; no invented version request or unsolicited wire traffic.

**Required next evidence:** with probe already running and auto-test enabled, unplug cable for ~5s then replug and send full log, including watchdog or RX/TX messages.
