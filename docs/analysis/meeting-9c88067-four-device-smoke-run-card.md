# Meeting 四机烟测 Run Card — tip `9c88067`

**Status:** 烟测完成 — **FAIL（仅 Host 可听）** · log 已停  
**Date:** 2026-10-09  
**Build tip:** `9c88067`（PR #201 / P1–P3 desk 冻结 tip）  
**APK:** `talkback-install-9c88067/talkback-app/build/outputs/apk/debug/talkback-app-debug.apk`  
**Package:** `com.talkback.appprod`  
**听感：** 全程记为 **UNKNOWN**（本卡只收烟测 + log，不作听感合格裁决）  
**RCA：** `artifacts/meeting-9c88067-smoke-20261009-162029/RCA-only-host-hears.md`

---

## 设备角色

| 角色 | Serial | 型号备注 |
|------|--------|----------|
| **M01 Host** | `HTUBB21B09220661` | MLD_AL10（USB） |
| **M02 Peer** | `192.168.31.206:5555` | DSJ_A7S |
| **M03 Peer** | `192.168.31.115:5555` | g80l board |
| **M04 Peer** | `192.168.31.148:5555` | ELS_AN00 |

**Log 目录：** `docs/analysis/artifacts/meeting-9c88067-smoke-<STAMP>/`

---

## 前置检查（开测前打勾）

- [ ] 四机 `adb devices` 均为 `device`
- [ ] 四机同一 Wi‑Fi / 同 L3 网段
- [ ] 四机已装本次 debug APK（今日已装 Success）
- [ ] 四机时间大致同步
- [ ] establishment / 现场信任与日常 Meeting 一致（本卡不重做 provision）
- [ ] 四机 logcat 已在跑（见下方）

---

## 测试步骤（按顺序做）

### S0 — 清场（约 1 分钟）

1. 四机若有未结束 Meeting / 通话 → **全部结束**，退回主界面。  
2. 确认无人占用同一会议频道。  
3. 记录开始墙钟时间：`T0 = ________`

### S1 — 建会（Host）

1. **仅 M01** 打开 App → 进入 Meeting / 开会流程 → **创建并进入会议**（Host）。  
2. 等待 UI 显示自己在会中（约 10–20s）。  
3. **观察（记 PASS/FAIL）：** M01 未闪退；会议界面稳定。

### S2 — 三叶加入

1. **M02** 加入同一会议 → 等进会成功。  
2. **M03** 加入同一会议 → 等进会成功。  
3. **M04** 加入同一会议 → 等进会成功。  
4. **观察：** 四机花名册均可见 4 人（或产品等价展示）；无反复踢人/闪退。

### S3 — 轮流说话（核心烟测，约 3–5 分钟）

按固定顺序，每人连续说约 **10–15 秒** 可辨内容（数字+姓名即可）：

| 序 | 说话机 | 其余三机听 |
|----|--------|------------|
| T1 | M01 | M02/M03/M04 |
| T2 | M02 | M01/M03/M04 |
| T3 | M03 | M01/M02/M04 |
| T4 | M04 | M01/M02/M03 |

每步当场记三项（只记事实，不裁决「改善」）：

| 项 | 记法 |
|----|------|
| 能否听到对方说话 | Y / N / 断续 |
| 是否明显盖人声/咕嘟/杂音 | 有 / 无 / 不确定 |
| UI 是否掉人/结束异常 | Y / N |

### S4 — 双人重叠（约 1 分钟）

1. **M01 + M02** 同时说话约 20s。  
2. **M03、M04** 记：是否仍能分辨至少一路；是否崩/静音。

### S5 — 短浸泡（约 2 分钟）

1. 保持四人在会，**安静或轻声背景** 约 120s。  
2. 观察：无集体掉线、无全员静音、无闪退。

### S6 — 正常散会

1. **M01** 点 **结束会议**（或产品等价 End）。  
2. 确认 M02/M03/M04 均退出会议 UI。  
3. 记录结束墙钟：`T1 = ________`

### S7 — 停 log（测完再做）

告诉助手「测完了」→ 停四机 logcat → 打包 log 目录。

---

## 当场记录表（请填）

| 步骤 | 结果 | 备注 |
|------|------|------|
| S1 建会 | | |
| S2 四机齐 | | |
| T1 M01 说 | | |
| T2 M02 说 | | |
| T3 M03 说 | | |
| T4 M04 说 | | |
| S4 重叠 | | |
| S5 浸泡 | | |
| S6 散会 | | |

**总判（烟测）：** PASS / FAIL / 部分  
**听感：** UNKNOWN（固定）

---

## Log 关注标签（事后检索用，现场不必看）

```text
TalkbackCoordinator
MEETING_MEDIA_SHADOW
PROFILE01_SHADOW_RUNTIME
RC1_FIELD
AUDIBLE_OWNERSHIP
ConferenceSession
SIGABRT
AndroidRuntime
FATAL EXCEPTION
```

---

## 明确不做

- 不改编码器 / ULE / 增益 / 拓扑  
- 不把本次结果写成听感验收  
- 不把本次写成 Scheme B L1/L2 合入证明  
