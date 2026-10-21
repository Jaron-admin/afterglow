# 开发、构建与验收

普通用户请从[项目首页](../README.md)开始。本文保留构建工具、测试范围和部署边界；历史变更见[版本记录](CHANGELOG.md)，HA 维护见[HA 开发说明](home-assistant-development.md)。

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

输出 `dist/Afterglow-1.4.2.apk`。使用本机已有的 Android debug keystore；
若没有，先运行一次 Gradle debug 构建。签名文件、本机路径和构建目录均被 Git 忽略。
自己的正式发布应使用自行管理的签名密钥。CI 的 debug APK 与本机 APK 可能使用不同签名，不能互相直接覆盖升级。

## 验证

各版本的测试证据与仍需真机验收的项目见[验收记录](VALIDATION.md)。

Windows，JDK 17、Node.js：

```powershell
.\tests\run.ps1 -JavaHome F:\SDK\jdk-17
```

测试包括方向防抖、四向像素旋转、裁剪及色度、相机平面转换、真实生命周期协调器、
回环 TCP/UDP 的 HTTP/MJPEG/RTSP 音视频交互、实际网页脚本重连、事件前后录、
录像容量和锁定保护、文件异常隔离、音频资源释放、画面变化与声音阈值。
Android 系统类在 JVM 测试中仅提供必要替身；这些测试不能验证物理摄像头、EGL、硬件编码器和 UI 绘制。
有声 MP4、音画同步、第三方 RTSP 播放器、SD 卡拔插和长时间运行仍需在目标手机验证。

参见 [生命周期测试](../tests/STREAM_LIFECYCLE.md)、[观看端测试](../tests/viewer/README.md)。
GitHub Actions 自动执行回归测试、构建和 Android lint，并保留 debug APK artifact。

## 网络与权限

本应用用于可信局域网。可选的访问验证仅保护 HTTP/MJPEG，RTSP 未提供认证；HTTP 本身未加密。
不要直接把这些端口映射到公网。摄像头权限用于取流，麦克风权限仅在启用声音时申请，
前台通知和唤醒锁用于维持运行，局域网扫描用于发现同网段设备。
开机启动受 Android 版本和厂商后台策略限制；需要时请在系统电池设置中允许后台运行。

仓库不包含签名密钥、个人配置或真实摄像头画面。`tools/fake_device.py` 的调试图片需自行放到被忽略的 `tools/frame.jpg`。
