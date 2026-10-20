<p align="center"><img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Afterglow icon"></p>
<h1 align="center">余光 · Afterglow</h1>
<p align="center">让闲置的安卓手机，继续守望。</p>

Afterglow turns an old Android phone into a local-network camera, with RTSP/H.264 and MJPEG streaming. No account, cloud service, AndroidX or third-party runtime dependencies.

## 使用

1. 安装 APK，打开「余光」，授予摄像头权限。
2. 点击「开始推流」。摄像头选择和画面旋转就在预览下方。
3. 推流中调整摄像头、方向或画质，应用会自动重配；停止时调整只保存设置。
4. 在同一局域网的浏览器打开 `http://手机IP:8080/`，或用 VLC 打开 RTSP 地址。
5. 长说明放在右上角「帮助」，访问地址可点击复制。

开始使用可选 640 × 480、15 fps。设备摄像头、编码器及系统后台限制会影响实际效果。
切换摄像头、90°/270°旋转会重建视频链路，观看端可能短暂断开。
内置 MJPEG 查看器和网页会自动重连；第三方 RTSP 播放器是否恢复由其自身行为决定。

| 用途 | 地址 |
| --- | --- |
| 浏览器观看 | `http://手机IP:8080/` |
| RTSP / H.264 | `rtsp://手机IP:8554/live` |
| MJPEG | `http://手机IP:8080/video` |
| JPEG 快照 | `http://手机IP:8080/snapshot.jpg` |
| 状态 | `http://手机IP:8080/status` |

仅开启对应输出模式时，相应地址才可用。手机之间也可以从「附近设备」进入查看。
[Home Assistant 接入说明](homeassistant/README.md)。

## 1.1.0 的改动

- 余光 / Afterglow 名称、深色界面、暖色几何镜头图标，含 Android 8+ 自适应图标。
- 摄像头和旋转控制靠近预览；精简重复说明，画质参数紧凑排列。
- 设置自动生效，合并连续操作；预览丢弃旧会话的迟到画面。
- 启动、停止、重配串行执行，失败时释放已创建的摄像头、编码器、服务器和唤醒锁。
- 网页重连与会话检测；修正 MJPEG 分段边界及断连后的客户端清理。
- 修正 Android 4.4 的 H.264 编码器枚举路径，补充生命周期与真实网络协议回归测试。

## 构建

要求 JDK 17、Android SDK Platform 36、Build-Tools 36.0.0。
项目声明 `minSdk 19`、`targetSdk 33`；保留原安装包 ID `com.oldphone.ipcam`，同一签名下可覆盖升级并保留配置。
版本兼容声明不代替真机测试，尤其是相机和硬编码器的机型差异。

### Gradle

设置 `ANDROID_HOME`，或在不提交的 `local.properties` 中填写 `sdk.dir`。
仓库包含 Gradle 8.13 wrapper，首次运行会下载 Gradle 与 AGP 8.11.1 依赖。

```powershell
.\gradlew.bat assembleDebug lintDebug
```

Linux/macOS 用 `./gradlew assembleDebug lintDebug`。产物：
`app/build/outputs/apk/debug/app-debug.apk`。

### Windows 离线 SDK 工具链

已经有 SDK/JDK 时，可直接用 `aapt2 → javac → d8 → zipalign → apksigner`：

```powershell
.\build-apk.ps1 -Sdk F:\SDK -Jdk F:\SDK\jdk-17
```

输出 `dist/Afterglow-1.1.0.apk`。使用本机已有的 Android debug keystore；
若没有，先运行一次 Gradle debug 构建。签名文件、本机路径和构建目录均被 Git 忽略。
自己的正式发布应使用自行管理的签名密钥。CI 的 debug APK 与本机 APK 可能使用不同签名，不能互相直接覆盖升级。

## 验证

Windows，JDK 17、Node.js：

```powershell
.\tests\run.ps1 -JavaHome F:\SDK\jdk-17
```

测试包括真实生命周期协调器、回环 TCP 的 HTTP/MJPEG/RTSP 交互和实际网页脚本的重连行为。
Android 系统类在 JVM 测试中仅提供必要替身；这些测试不能验证物理摄像头、EGL、硬件编码器和 UI 绘制。

参见 [生命周期测试](tests/STREAM_LIFECYCLE.md)、[观看端测试](tests/viewer/README.md)。
GitHub Actions 自动执行回归测试、构建和 Android lint，并保留 debug APK artifact。

## 网络与权限

本应用用于可信局域网。可选的访问验证仅保护 HTTP/MJPEG，RTSP 未提供认证；HTTP 本身未加密。
不要直接把这些端口映射到公网。摄像头权限用于取流，前台通知和唤醒锁用于维持运行，局域网扫描用于发现同网段设备。
开机启动受 Android 版本和厂商后台策略限制；需要时请在系统电池设置中允许后台运行。

仓库不包含签名密钥、个人配置或真实摄像头画面。`tools/fake_device.py` 的调试图片需自行放到被忽略的 `tools/frame.jpg`。