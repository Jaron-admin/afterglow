# Codec discovery regression

Run from the repository root:

```powershell
.\tests\codec\run.ps1 -JavaHome F:\SDK\jdk-17 -AndroidJar F:\SDK\platforms\android-36\android.jar
```

The runner compiles the real encoder against Android's SDK jar, then executes its discovery method with three small discovery-boundary stubs. On simulated API 19 the API 21 constructor throws `NoSuchMethodError`, reproducing the original bug. Both API 19 and API 21 must return hardware AVC encoders first, retain software AVC fallback, and omit decoders and other codec types. No MediaCodec or hardware encoding behavior is mocked.

This tests discovery routing and filtering; actual codec configuration and rendering still require Android devices.
