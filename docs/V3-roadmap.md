# Talkback V3 路线图

## 媒体

- SFU-lite：组呼超过 5 模块时引入可选媒体转发节点。
- 明确 `MediaTopologyPolicy` 配置与降级策略。

## 安全

- DTLS-SRTP 严格证书或预共享指纹。
- 信令 HMAC-SHA256 替代当前 SHA256(canonical+secret)。

## 信令

- 可靠信令通道（TCP 或 WebSocket over RF-mesh IP 子网）。
- 大 SDP 分片与重传。

## 运维

- WebRTC `getStats` 周期采样写入 QoS。
- 远程日志采集与 `traceId` 关联。

## 硬件

- `AudioRouter` 完整实现：蓝牙 PTT、USB 声卡、物理手柄。
