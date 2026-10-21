# 余光 · Afterglow 接入 Home Assistant

Afterglow 1.4.0 附带专用 HA 自定义集成：实时摄像头、设备地址自动更新，以及手机录像的媒体浏览和播放。已有的 Tailscale 可以继续使用。

## 安装

1. 手机升级到 Afterglow 1.4.0，选择 RTSP + MJPEG（推荐）、RTSP 或 MJPEG，启动采集。需要录像时在「录像与检测」开启连续或事件录像。
2. 将本目录的 custom_components/afterglow 整个文件夹复制到 HA 的 /config/custom_components/afterglow。不是附加组件/HA App。
3. 重启 HA，在「设置 → 设备与服务」处理发现的 Afterglow 设备；也可以「添加集成 → Afterglow」手动填写手机 IP 和 HTTP 端口8080，或粘贴手机显示的 http://IP:8080/ 地址。
4. 如果手机开启「访问验证」，填写相同用户名和密码；未开启则留空。设备固定名称类似 Afterglow-a1b2c3d4。
5. 实时画面使用新增 camera 实体。打开 HA 侧栏「媒体 → Afterglow → 设备」，选择已完成片段播放，支持拖动进度和浏览下一页。精简 YAML 安装需启用 media_source:；RTSP 需要 stream:。

这是仓库附带的自定义集成，不是 HA 官方内置集成；不需要 HACS。更新时替换同一文件夹并重启 HA。旧 Generic Camera 实体可暂时保留，对照确认新画面正常后自行停用。

## 重启与 IP 变化

手机保存独立的设备 UUID。局域网 DNS-SD 广播携带这个身份；HA 先读取新地址的公开身份接口，确认同一设备，再更新地址并重载。camera 实体身份不依赖 IP，无需手工重建卡片。

这不会改变路由器的 DHCP 分配，也不会产生保证可解析的固定 .local 主机名。复制给 VLC 等第三方软件的数字 IP 地址仍可能变化；要让这些地址也固定，可以在路由器里做 DHCP 地址保留。普通 Android 应用不能替路由器保证固定分配。

HA 和手机需要能互通 mDNS。访客 Wi-Fi、客户端隔离、不同 VLAN、未采用宿主网络的容器可能阻挡发现。这时可在集成菜单「重新配置」填新 IP，或配置网络 mDNS 转发/地址保留。跨 Tailscale 广播发现不在本功能范围内；让家里的 HA 发现家里的手机即可。

清除应用数据或卸载重装会产生新身份，需重新添加设备。开机自动恢复采集还需要开启应用内开机启动并允许后台运行；厂商电池策略仍可能阻止它。

## Tailscale 远程播放

保持现有 Tailscale 连接，用原来能访问的 HA 地址打开 HA。录像链路为：

外面的手机 → Tailscale → 家里的 HA → Afterglow 手机

HA 在服务器端读取并转发视频，浏览器拿到 HA 自己的播放路径，不需要直接访问录像手机的 IP。无需为 Afterglow 映射公网端口。若能访问 HA 而录像打不开，先确认 HA 本身能访问手机8080端口、手机仍在采集。

以后如果希望不连 VPN 就能打开 HA，Home Assistant Cloud 是另一种入口；此次没有创建、购买或配置云服务。参见 [HA 官方远程访问说明](https://www.home-assistant.io/docs/configuration/remote/)。

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

## 原有通用摄像头接入

不安装专用集成时仍可按 [Generic Camera](https://www.home-assistant.io/integrations/generic/) 或 [MJPEG IP Camera](https://www.home-assistant.io/integrations/mjpeg/) 文档手动添加，替换示例 IP：

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
