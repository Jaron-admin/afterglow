# Home Assistant 开发与维护

普通用户使用[四步实时画面指南](../homeassistant/README.md)。需要录像浏览时，用户指南中的 HACS 自定义仓库入口负责安装专用集成。本文保留手工部署、网络、认证和验收细节，供开发与维护使用。

## 仓库与 HACS 布局

运行时集成位于仓库根目录 `custom_components/afterglow/`；`homeassistant/` 仅保留使用文档与测试。旧版 `homeassistant/custom_components/afterglow/` 已迁移，不应同时安装两份。

HACS 自定义仓库使用 `https://github.com/Jaron-admin/afterglow`，类别为 Integration。发布前检查 `hacs.json`、集成 `manifest.json` 的版本和发布标签，并确认默认分支与发布内容包含上述运行时目录。这不表示已进入 HACS 默认仓库目录。HACS 结构与发行规则以[官方集成发布文档](https://www.hacs.xyz/docs/publish/integration/)为准。

## 手工安装（调试或维护）

检出需要调试的版本，将仓库根目录的 `custom_components/afterglow` 完整复制到 HA 配置目录的 `custom_components` 下。已有集成时替换同一路径，不要复制旧运行时目录或把整个仓库放入 HA 配置目录。完整重启 HA 后再添加或重载 Afterglow。

HA 的配置目录就是包含 `configuration.yaml` 的目录。安装方式不同，复制入口也不同：

| 安装方式 | 操作方法 |
| --- | --- |
| **HA OS：Samba share** | 在「设置 → 应用」（旧版称「附加组件」）安装 **Samba share**，按它的配置页设置用户名/密码并启动。在 Windows 文件资源管理器地址栏输入 `\\HA的IP\config`，用 Samba 凭据登录，在里面打开/创建 `custom_components`，粘贴 `afterglow`。macOS 可在 Finder「连接服务器」输入 `smb://HA的IP/config`。 |
| **HA OS：Studio Code Server** | 若你的设备支持该应用，可安装并启动它、打开 Web UI，在包含 `configuration.yaml` 的工作目录中创建 `custom_components`，上传/复制完整的 `afterglow` 文件夹。 |
| **HA OS：Terminal & SSH** | 已使用该应用的用户可在终端或 SSH 中，将解压后的集成目录复制到配置目录的 `custom_components/afterglow`。先确认该目录包含 `configuration.yaml`，不要复制到应用自己的临时目录。 |
| **HA Container / Docker** | 查看原有 Docker/Compose 的卷挂载，找出冒号右边为 `/config` 的条目。例如 `/srv/homeassistant:/config`，宿主机安装位置就是 `/srv/homeassistant/custom_components/afterglow`。使用宿主机文件管理器或 SSH 复制；Container 不提供 HA 应用商店。 |

以上入口见 [HA OS 官方文件访问说明](https://www.home-assistant.io/common-tasks/os/#configuring-access-to-files)；Container 挂载规则见 [官方安装说明](https://www.home-assistant.io/installation/linux/#install-home-assistant-container)。不知道配置目录在哪时，可在 HA「设置 → 系统 → 修复」右上角菜单打开「系统信息」，查看 Configuration directory。参见 [官方配置目录说明](https://www.home-assistant.io/docs/configuration/#to-find-the-configuration-directory)。

从 HA 看到的最终结构必须是：

```text
/config/
├── configuration.yaml
└── custom_components/
    └── afterglow/
        ├── manifest.json
        ├── __init__.py
        ├── api.py
        ├── camera.py
        ├── config_flow.py
        ├── const.py
        ├── media_source.py
        ├── streaming.py
        ├── strings.json
        ├── views.py
        ├── brand/
        │   └── icon.png
        └── translations/
            ├── en.json
            └── zh-Hans.json
```

关键检查是 **`/config/custom_components/afterglow/manifest.json` 能直接找到**。`/config/homeassistant/custom_components/...`、`/config/custom_components/afterglow/afterglow/...` 都是放错层级；不要覆盖原有 `configuration.yaml`。

## 重启与添加设备

1. 在 HA「设置 → 系统」右上角菜单选择「重启 Home Assistant」，执行完整重启。不要只刷新浏览器、重载 YAML 或选择「快速重载」。等 HA 恢复后再继续。Container 也可重启原有 HA 容器；无需删除或重新创建容器。
2. 打开「设置 → 设备与服务」。若出现名称类似 `Afterglow-a1b2c3d4` 的发现卡片，点击配置，核对名称与手机一致。
3. 没有发现卡片时，点「添加集成」，搜索 **Afterglow**。手动表单填写如下：

| 字段 | 填什么 |
| --- | --- |
| 主机名、IP 或 HTTP 地址 | 粘贴手机的完整 HTTP 基础地址，如 `http://10.0.0.50:8080/`；也可只填 `10.0.0.50`。不要填 RTSP 地址、`/video` 路径或 HA 自己的 IP。 |
| HTTP 端口 | **8080**。若粘贴地址中已有端口，以地址中的端口为准。 |
| HTTP 用户名、密码 | 手机开启「访问验证」时，填写手机上设置的同一组凭据；未开启则都留空。与 HA 登录、Samba 登录无关。 |

提交成功后会生成一个设备及摄像头实体。更新集成时，替换同一 `afterglow` 文件夹的文件并完整重启 HA；不用重新创建设备。原 Generic Camera 实体可先保留，对照确认新画面正常后自行停用，不会自动迁移旧卡片。

## 实时画面与录像行为

在「设置 → 设备与服务 → Afterglow → 设备」中打开摄像头实体查看画面，也可将它加入仪表盘。RTSP 实时画面可带声音，前提是手机开启麦克风；MJPEG 始终无声。

历史录像在 **HA 侧栏「媒体 → Afterglow → 设备」**。选择片段后播放，可拖动进度条；每页最多 100 条，更多片段在底部 **More recordings →**。录像仍保存在手机选择的存储里，不会因为添加集成而自动备份到 HA。

第一次验证可先录一小段，停止采集使当前片段完成，再点击「开始」恢复网络服务，随后刷新 HA 媒体列表。保持使用 RTSP + MJPEG、RTSP 或 MJPEG 任一网络模式；「仅本地录像」不提供网络访问。精简 YAML 安装若没有相关功能，请检查 `media_source:` 与 `stream:` 是否启用；普通安装使用界面添加即可。

## 10 开头、Wi-Fi 与 VPN 地址

`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16` 都是私有地址范围。`10.0.0.50` 完全可能是路由器给手机分配的正常 Wi-Fi 地址，不必改成 `192.168`。判断依据是 HA 能否到达该地址。`100.64.0.0/10` 属于共享地址空间，不能仅凭这个范围认定是 Tailscale。参见 [IANA IPv4 地址登记](https://www.iana.org/assignments/iana-ipv4-special-registry/)。

应用列出系统当前可见的 IPv4 地址，可在「选择访问地址」中选择用于显示和复制的地址。它不能生成系统没有分配的地址，选择地址也不修改 Android 网络路由或路由器 DHCP。列表中的「VPN/隧道」是识别线索，不是对某个 VPN 品牌的认证；是否属于 Tailscale，应在 Tailscale 应用或管理界面核对。

通常先选家里 HA 能访问的 Wi-Fi/有线地址。切换网络后重新检查地址；如果只看到 `10.x`，可对照手机系统的 Wi-Fi 详情。使用子网路由远程访问时，录像手机不一定安装了 VPN，所以也不一定有单独的 Tailscale 地址。固定设备 UUID 与固定数字 IP 是两回事，后者仍需路由器地址保留。

## 重启与 IP 变化

手机保存独立的设备 UUID。局域网 DNS-SD 广播携带这个身份；HA 先读取新地址的公开身份接口，确认同一设备，再更新地址并重载。camera 实体身份不依赖 IP，无需手工重建卡片。

这不会改变路由器的 DHCP 分配，也不会产生保证可解析的固定 .local 主机名。复制给 VLC 等第三方软件的数字 IP 地址仍可能变化；要让这些地址也固定，可以在路由器里做 DHCP 地址保留。普通 Android 应用不能替路由器保证固定分配。

HA 和手机需要能互通 mDNS。访客 Wi-Fi、客户端隔离、不同 VLAN、未采用宿主网络的容器可能阻挡发现。这时可在集成菜单「重新配置」填新 IP，或配置网络 mDNS 转发/地址保留。跨 Tailscale 广播发现不在本功能范围内；让家里的 HA 发现家里的手机即可。

清除应用数据或卸载重装会产生新身份，需重新添加设备。开机自动恢复采集还需要开启应用内开机启动并允许后台运行；厂商电池策略仍可能阻止它。

## Tailscale 远程播放

保持现有 Tailscale 连接，用原来能访问的 **HA 地址**打开 HA。录像链路为：

外面的手机 → Tailscale → 家里的 HA → Afterglow 手机

HA 在服务器端读取并转发视频，浏览器拿到 HA 自己的播放路径，不需要直接访问录像手机的 IP。只要现有 Tailscale 入口或已配置好的子网路由能到达 HA，且 HA 能访问录像手机，就不要求在录像手机上安装 Tailscale。子网路由允许访问未安装 Tailscale 的局域网设备，见 [Tailscale 官方说明](https://tailscale.com/docs/features/subnet-routers)。

无需为 Afterglow 映射公网端口。若能访问 HA 而录像打不开，先确认 HA 本身能访问手机 `8080` 端口、手机仍在网络模式下采集；RTSP 实时流还需要 HA 能访问 `8554`。自动发现使用家里的局域网 mDNS，不会跨 Tailscale 广播；发现失败时可在 HA 手动输入手机的可达地址。

## 常见问题：按现象排查

| 现象 | 检查与处理 |
| --- | --- |
| 「添加集成」中没有 Afterglow | 确认所部署版本包含根目录集成，文件没有套错层级，`/config/custom_components/afterglow/manifest.json` 存在，全部 Python/JSON 文件及翻译目录都已复制，然后完整重启 HA。仍无结果时，在「设置 → 系统 → 日志」搜索 `afterglow`，检查文件缺失或版本兼容错误。 |
| 搜得到集成，但没有自动发现卡片 | 直接手动填手机 HTTP 地址。确认 HA 和手机处于可互通的局域网；访客网络、无线客户端隔离、VLAN、容器网络可能阻挡 mDNS。跨 VPN 不会自动发现。 |
| 浏览器打不开手机 HTTP 地址 | 手机是否已点「开始」？是否选了网络输出模式？地址是否来自当前网络？先从同网段电脑测试 `http://手机IP:8080/api/info`，再确认 HA 主机也能访问。只有电脑能打开，不代表 HA 的网络或防火墙已放行。 |
| HA 报「无法连接」 | 在上述 HTTP 测试通过后，检查手动表单填的是手机而不是 HA 的地址，端口为 8080；基础地址不要带 `/video`、用户名或密码。 |
| HA 报用户名或密码错误 | 对照手机「访问验证」中的用户名/密码；手机未启用时留空。修改手机凭据后，在 HA 按提示更新认证。不要使用 HA 或 Samba 的密码代替。 |
| 设备已添加，但实时画面不可用 | 手机保持采集，确认当前模式；RTSP + MJPEG 最便于同时取得流和快照。HTTP 能通而 RTSP 不通时，继续检查 HA 到手机 8554 端口。 |
| 媒体列表为空 | 在手机录像列表确认有已完成片段，并确认「录像与检测」选中的存储位置一致。正在写入的片段不列出；停止结束片段后，要再开始采集恢复网络服务。 |
| SD 卡取下后无法浏览 | 插回原存储并确认手机能读到录像。集成不将存储错误伪装成空库，也不会自动改用手机内部存储。 |
| 停止采集后连历史录像也打不开 | 历史录像也通过手机的网络服务读取。重新选择网络输出模式并开始采集；录像开关本身可以关闭。 |
| 手机换 IP 后设备不可用 | 同网段发现正常时会按设备身份自动更新。否则在 HA 集成菜单「重新配置」中填写新地址，或在路由器为手机保留 DHCP 地址。不要仅因 IP 变化删除设备重建实体。 |
| 家里能播放、外出不能播放 | 先确认现有 Tailscale/子网路由能打开 HA；再确认 HA 到余光手机的连接。自动发现无需在外出的手机上运行，也无需让外出浏览器直接访问摄像头 IP。 |

## 录像与输出模式

- 只显示当前选择存储中的已完成片段。正在录制、异常残留和未登记文件不公开。
- 每页100条，最新时间在前。播放不把整段录像缓存在 HA 内存中。
- 录像只读：不提供删除、解锁、云端上传或修改录制配置。
- 停止采集会关闭网络服务；「仅本地录像」不提供网络访问或广播。浏览历史录像时须使用任一网络输出模式并保持采集，录像开关可以关闭。
- RTSP-only 也开启身份/录像 HTTP 服务，但不开放 MJPEG/快照。推荐 RTSP + MJPEG，方便 HA 同时获取流与预览。
- SD 卡移除时录像浏览报存储不可用，不伪装成空列表、不自动切换存储。录像错误不会阻止 HA 配置现有实时摄像头。

## 访问验证

手机可选 HTTP 用户名/密码保护录像、MJPEG、快照和状态。/api/info 仅公开设备编号、名称、端口和能力，供自动重连前确认身份，不包含录像列表、密码或文件路径。

HA 代理使用 HA 自身认证和媒体签名，不把手机密码放入浏览器播放 URL。HTTP 身份检查不是密码学设备认证；应用仍面向可信局域网/VPN。RTSP 仍无认证，HTTP 未加密，不应直接映射这些端口到公网。

## Generic Camera 与专用集成的区别

普通用户主路径使用 [Generic Camera](https://www.home-assistant.io/integrations/generic/) 文档配置；仅需无声 MJPEG 时也可参考 [MJPEG IP Camera](https://www.home-assistant.io/integrations/mjpeg/)。以下用于调试，替换示例 IP：

| 字段 | 示例 |
| --- | --- |
| Still Image URL | http://192.168.1.50:8080/snapshot.jpg |
| Stream Source | rtsp://192.168.1.50:8554/live |
| MJPEG URL | http://192.168.1.50:8080/video |
| RTSP transport | tcp |

通用集成不会使用 Afterglow 身份更新 IP，也不提供手机录像库。快照/MJPEG 需要对应输出模式；RTSP-only 可仅填流地址。

## 验证范围与验收

自动化测试覆盖手机真实 HTTP 套接字、范围请求、身份持久化和异步发现生命周期。HA 侧使用真实 aiohttp 服务测试代理和身份检查，对 HA 框架接口使用替身；接口参照 HA 2026.10.2 源码，配置表单支持 probatio/voluptuous 导入。这不等于验证所有历史 HA 版本。

目标部署仍需验收实际手机 NSD、重启换 IP、HA 前端播放和 Tailscale 链路：先播已完成录像并拖动进度，再重启手机/换 DHCP 地址，确认同一 HA 设备和 camera 实体恢复播放。

APK 使用本机已有 debug 签名。若旧包来自不同签名的 CI 构建，Android 可能拒绝覆盖；不要为解决签名冲突直接卸载，应用专属录像会随卸载删除，应先导出重要录像并确认升级方式。

官方扩展点：[Media Source](https://developers.home-assistant.io/docs/core/platform/media_source)、[网络发现](https://developers.home-assistant.io/docs/network_discovery/)。
