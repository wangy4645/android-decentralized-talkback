# 架构裁定：今日装包 vs Scheme B 分层

**日期：** 2026-10-09  
**听感：** 仍为 `UNKNOWN`（装包 ≠ 听感合格）

## 建议（已采纳执行）

| 轨道 | 裁定 |
|------|------|
| **今日装包** | 用冻结 tip **`9c88067`**（PR #201 / P1–P3 desk 已合入的 Meeting GA 线）干净树 `assembleDebug` + 四机安装 |
| **不今日做** | L2 Slice-M/F 抠闭包、整包搬 `1ab10ae`、改脏工作树冒充冻结 tip |
| **Scheme B L1** | `#203` 仍独立；合入与否不挡今日装包 |
| **Scheme B L2** | 改为 **A′：可编译 GA 引入单元**（`faf8884` + 同平面栈 + Gradle/`concentus`），不再假装「单 commit 小 PR」 |

## 理由（短）

- `main` 仅 L0 → 不能测 Meeting。  
- L1 可编但无 GA 媒体栈 → 不是今日 Meeting 装包目标。  
- L2 单独切片已证伪 → 继续抠会拖死「今天装包」。  
- `9c88067` 是程序冻结 tip，desk 门已签，适合烟测/装包。

## 明确非结论

装包成功 **不** 表示咕嘟/盖人声改善，也 **不** 表示 Scheme B 已落到 `main`。

---

## 执行记录（2026-10-09）

| 项 | 结果 |
|----|------|
| Worktree | `talkback-install-9c88067` @ `9c88067` |
| Overlay | 仅补本地未入库 `org.webrtc.TalkbackApmReverseFeed.java`（JNI 壳；无改业务逻辑） |
| `assembleDebug` | **PASS** |
| APK | `talkback-install-9c88067/talkback-app/build/outputs/apk/debug/talkback-app-debug.apk` |
| 安装 | 4 台 **Success**（HTUBB21B09220661 · 192.168.31.115 · .148 · .206） |

听感仍 **UNKNOWN**。
