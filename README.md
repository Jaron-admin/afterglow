<p align="center"><img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Afterglow icon"></p>
<h1 align="center">余光 · Afterglow</h1>
<p align="center">让闲置的安卓手机，继续守望。</p>

把旧安卓手机变成家里的摄像头：实时观看、本地录像、画面变化与声音触发，无需注册账号。

**当前版本：1.4.2。** [下载安装包](https://github.com/Jaron-admin/afterglow/releases/latest)

## 开始使用

1. 安装并打开余光，授予摄像头权限。
2. 选择镜头、画面方向和清晰度，点击「开始」。默认 720p、15 fps，可按手机性能调整。
3. 在「访问地址」选择观看设备能访问的地址，点按复制；同一局域网的浏览器可直接打开 HTTP 地址观看。
4. 需要保存录像时，打开「录像与检测」，选择连续或事件录像及存储位置。需要声音时开启麦克风；MJPEG 画面无声。

预览保留完整取景，半透明遮罩表示远端画面会裁掉的部分。「仅本地录像」只保存到手机，不提供网络观看。重要录像请先导出，卸载应用会删除应用内录像和设置。

## 接入 Home Assistant：四步看实时画面

使用 HA 自带的 **Generic Camera（通用摄像头）** 即可看实时画面。

1. 手机和 HA 连到同一局域网。在余光选择 **RTSP + MJPEG**，点击「开始」。
2. 在 HA 打开「设置 → 设备与服务 → 添加集成」，搜索 **Generic Camera（通用摄像头）**。
3. 从手机「访问地址」复制 **JPEG 快照**与 **RTSP** 地址，分别粘贴到 **Still Image URL（静态图像 URL）** 和 **Stream Source（流来源）**。手机开启「访问验证（HTTP）」时，填写相同用户名、密码，认证选 **Basic**；未开启则留空。RTSP 传输保持 **TCP**。
4. 保存并按提示完成配置，打开新增摄像头查看实时画面，再按需添加到仪表盘。

地址格式如下，`手机IP` 用手机实际显示的地址替换；也可以直接复制手机上的完整地址：

| HA 字段 | 地址 |
| --- | --- |
| Still Image URL | `http://手机IP:8080/snapshot.jpg` |
| Stream Source | `rtsp://手机IP:8554/live` |

字段说明可对照 [HA 官方 Generic Camera 指南](https://www.home-assistant.io/integrations/generic/)。此方式只看实时画面，不会显示手机录像库；需要录像浏览时再看下面的可选功能。

## 常见问题

**连不上？** 确认手机已点「开始」、模式为 RTSP + MJPEG，HA 能访问手机所选地址。`10.x.x.x` 也可以是正常的局域网地址；若开启了访问验证，核对用户名、密码和 Basic 认证。

**黑屏或卡顿？** 核对两条地址没有填反，RTSP 传输为 TCP，并打开摄像头的实时画面。改善 Wi-Fi 信号；旧手机可先降低分辨率再比较。

**手机 IP 变了怎么办？** 在 Generic Camera 的配置中更新两条地址；也可在路由器为手机保留地址，减少变动。

## 进阶可选：在 HA 浏览手机录像

需要录像浏览及自动跟随设备地址变化时，再通过 HACS 安装 **Afterglow**。尚未安装 HACS 的用户先按 [HACS 官方安装指南](https://www.hacs.xyz/docs/use/download/download/)完成安装与配置。

在 **HACS → 右上角菜单 → 自定义仓库**中填入 `https://github.com/Jaron-admin/afterglow`，类别选 **Integration（集成）**并添加。随后打开 Afterglow，点击 **Download（下载）**，完整重启 HA，再到「设置 → 设备与服务 → 添加集成 → Afterglow」配置手机。可选择发现的设备，或填写手机 HTTP 地址；开启访问验证时使用手机上的同一组凭据。这是 HACS 自定义仓库，需要先添加仓库。操作入口见 [HACS 官方说明](https://www.hacs.xyz/docs/faq/custom_repositories/)。

安装后在「媒体 → Afterglow → 设备」播放录像、拖动进度。只显示手机当前所选存储中已完成的片段；浏览时保持手机在网络模式下采集。原来的 Generic Camera 不会自动转换为 Afterglow。

外出时可继续通过现有 Tailscale 打开家里的 HA，由 HA 连接余光手机。

## 更多说明

[HA 使用指南](homeassistant/README.md) · [开发、构建与测试](docs/development.md) · [HA 开发与维护](docs/home-assistant-development.md) · [版本记录](docs/CHANGELOG.md)
