# Codec discovery regression

Run from the repository root:

```powershell
.\tests\codec\run.ps1 -JavaHome F:\SDK\jdk-17 -AndroidJar F:\SDK\platforms\android-36\android.jar
```

The runner compiles the real encoder against Android's SDK jar, then executes its discovery method with small discovery-boundary stubs. On simulated API 19 the API 21 constructor throws `NoSuchMethodError`, reproducing the original bug. Both API 19 and API 21 must return hardware AVC encoders first, retain software AVC fallback, and omit decoders and other codec types.

The lifecycle tests additionally use a `MediaCodec` boundary double to exercise delayed output/EOS, exceptions while acquiring or consuming output, timeout cleanup, and output backpressure. In the backpressure fixture a frame completes between camera callbacks and holds a bounded codec slot until its output is released. The real encoder must deliver both camera frames, which fails with input-before-drain ordering and passes with drain-before-input ordering. This is a bounded-buffer regression model, not a measurement of a device codec. Slice/header and timestamp safety tests use the real parser.

Actual codec configuration, throughput, rendering, and vendor behavior still require Android devices.
