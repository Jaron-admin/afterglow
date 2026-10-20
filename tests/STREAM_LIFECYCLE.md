# Stream lifecycle regression tests

These tests run the production `StreamLifecycle` coordinator with a deterministic serial queue and resource owner. Requests injected during `open()` reproduce the interleavings of a blocked native camera open without Android dependencies.

From the repository root in PowerShell:

```powershell
& 'F:\SDK\jdk-17\bin\javac.exe' -encoding UTF-8 -d build\lifecycle-tests app\src\main\java\com\oldphone\ipcam\StreamLifecycle.java tests\java\com\oldphone\ipcam\StreamLifecycleTest.java
& 'F:\SDK\jdk-17\bin\java.exe' -cp build\lifecycle-tests com.oldphone.ipcam.StreamLifecycleTest
```

Covered behaviors: stop during open releases partial resources; stopped settings events cannot restart streaming; bursts of settings changes occupy one queue entry and apply the newest generation; startup failure cleans up and permits an explicit retry; rapid stop/start never overlaps resources; superseded failures cannot cancel newer settings; destruction permanently rejects starts.

Camera hardware, EGL, MediaCodec and Android service scheduling still require device verification. A camera/rotation change rebuilds the camera and streaming endpoints, so existing viewers may reconnect briefly. `running` means a stream is requested (including initial startup and reconfiguration), `reconfiguring` means that request has not settled, and each JPEG carries its session generation for stale-frame rejection.
