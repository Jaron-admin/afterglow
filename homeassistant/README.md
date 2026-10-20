# 余光 · Afterglow 接入 Home Assistant

先在手机启动推流，确认 Home Assistant 与手机可以互相访问。下面的 `192.168.1.50` 是示例地址，请替换为应用显示的手机 IP。

## Generic Camera（RTSP）

按 [Home Assistant 官方 Generic Camera 文档](https://www.home-assistant.io/integrations/generic/)，在「设置 → 设备与服务 → 添加集成」中添加 Generic Camera，填写：

| 字段 | 值 |
| --- | --- |
| Still Image URL | `http://192.168.1.50:8080/snapshot.jpg` |
| Stream Source | `rtsp://192.168.1.50:8554/live` |
| RTSP transport | `tcp` |

同时填写以上两个 URL 时，余光应选择 RTSP + MJPEG 输出模式。
只开启 RTSP 时可仅填 Stream Source；Home Assistant 需要配置 stream 集成。

## MJPEG IP Camera

按 [Home Assistant 官方 MJPEG IP Camera 文档](https://www.home-assistant.io/integrations/mjpeg/)，添加 MJPEG IP Camera：

- MJPEG URL：`http://192.168.1.50:8080/video`
- Still Image URL：`http://192.168.1.50:8080/snapshot.jpg`
- 在余光启用 HTTP 访问验证后，填写对应用户名和密码。

HTTP 验证不保护 RTSP。不要把应用端口直接暴露到公网。

## 方向与断连

在余光预览旁调整方向会改变实际视频数据。90°/270°旋转同时交换输出宽高，观看端需要重新连接并读取新的流参数。
手机 IP 变化时需更新集成地址；可以在路由器中为手机保留固定的 DHCP 地址。
路由器的客户端隔离功能也可能阻止设备互访。

配置方式参考官方文档，查阅日期：2026-10-20。该仓库的自动化测试不运行 Home Assistant。