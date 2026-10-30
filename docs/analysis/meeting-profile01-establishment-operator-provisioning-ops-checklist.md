# Meeting Profile01 — Operator Provisioning 命令级操作清单

**Parent:** `meeting-profile01-establishment-operator-provisioning-recovery-run-card.md`  
**Status:** **AUTHORIZED / EXECUTABLE** (2026-09-08)

```text
目标（四步，任一步 FAIL 即停）
  1. M01 生成/确认 establishment Keystore identity
  2. M02 生成/确认 establishment Keystore identity
  3. 双方 evidence → authority → PAP 下发含双方 binding 的 Profile revision
  4. PRE-1..PRE-4 → 重跑 EP1–EP5

禁止：为跑通 EP 去改 P1-A/B/C、Factory、fallback
```

---

## 0. 前置条件

| 项 | 要求 |
|----|------|
| 设备 | M01=`HTUBB21B09220661`，M02=`DSJ-2407041` |
| APK | 含 `EpEstablishmentOperatorProvisionInstrumentedTest` 的 candidate build（`talkback-app` androidTest） |
| Signing FTPH | 两台均已 PASS（`operational-trust-anchor.bin` + `accepted-local-trust-state.bin` 存在） |
| adb | 两台在线，`moduleId` 已配置（M01 / M02） |

**安装（若尚未安装含 operator instrumentation 的 build）：**

```powershell
cd d:\workspace\project\talkback\talkback
./gradlew :talkback-app:installProdDebug :talkback-app:installProdDebugAndroidTest
```

---

## 1. 版本约定（operator 预分配）

首次 provisioning 使用 **Keystore alias version = 1**（两台各自独立 namespace）：

```text
M01 Keystore alias: talkback-establishment-M01-v1
M02 Keystore alias: talkback-establishment-M02-v1
```

Authority `taskProfileRevision` 默认 **20**（须高于 signing FTPH revision 10）。

最终 `establishmentKeyVersion` 以 `PROVISIONING_MANIFEST.txt`（authority 输出）为准，**禁止**从 `EP_PROBE` 反填。

---

## 2. 一键编排（推荐）

```powershell
cd d:\workspace\project\talkback\talkback\scripts
.\ep-establishment-operator-provision-all.ps1 `
    -EstablishmentKeyVersion 1 `
    -TaskProfileRevision 20 `
    -M01Serial HTUBB21B09220661 `
    -M02Serial DSJ-2407041
```

成功输出 `PRE_CHECK.txt` VERDICT=PASS 后，继续 §6 EP Field Run #2。

---

## 3. 分步命令（任一步 FAIL 停在该步）

### Step 1 — M01 Keystore + evidence

```powershell
.\ep-establishment-operator-provision-device.ps1 `
    -Serial HTUBB21B09220661 `
    -EstablishmentKeyVersion 1
```

**PASS：** instrumentation OK；logcat 含 `EP_OP_PROBE action=PROVISION_EVIDENCE ... outcome=READY`；设备上存在 `files/gbc-trust/EP_OPERATOR_EVIDENCE.txt`。

```powershell
.\ep-establishment-operator-pull-evidence.ps1 `
    -Serial HTUBB21B09220661 `
    -ModuleLabel M01 `
    -BatchDir d:\workspace\project\talkback\talkback\logs\ep-operator-batch-<stamp>
```

### Step 2 — M02 Keystore + evidence

```powershell
.\ep-establishment-operator-provision-device.ps1 `
    -Serial DSJ-2407041 `
    -EstablishmentKeyVersion 1

.\ep-establishment-operator-pull-evidence.ps1 `
    -Serial DSJ-2407041 `
    -ModuleLabel M02 `
    -BatchDir d:\workspace\project\talkback\talkback\logs\ep-operator-batch-<stamp>
```

### Step 3 — Workstation authority 签名 + 双端 PAP accept

```powershell
.\ep-establishment-operator-authority-batch.ps1 `
    -BatchDir d:\workspace\project\talkback\talkback\logs\ep-operator-batch-<stamp> `
    -TaskProfileRevision 20
```

**产出：**

```text
<BatchDir>/M01-evidence.txt
<BatchDir>/M02-evidence.txt
<BatchDir>/establishment-signed-M01.bin
<BatchDir>/establishment-signed-M02.bin
<BatchDir>/PROVISIONING_MANIFEST.txt    ← PRE-3 唯一权威来源
```

```powershell
.\ep-establishment-operator-accept-device.ps1 `
    -Serial HTUBB21B09220661 -ModuleLabel M01 -BatchDir <BatchDir>

.\ep-establishment-operator-accept-device.ps1 `
    -Serial DSJ-2407041 -ModuleLabel M02 -BatchDir <BatchDir>
```

**PASS：** 两台均存在 `accepted-local-establishment-trust-state.bin`；`EP_OPERATOR_ACCEPT.txt` 含 `Accepted`。

### Step 4 — PRE-1..PRE-4

```powershell
.\ep-establishment-operator-pre-check.ps1 `
    -BatchDir <BatchDir> `
    -M01Serial HTUBB21B09220661 `
    -M02Serial DSJ-2407041
```

| ID | 检查 | PASS |
|----|------|------|
| PRE-1 | establishment store | 两台 `accepted-local-establishment-trust-state.bin` 存在 |
| PRE-2 | Keystore identity | instrumentation `verifyLocalKeystoreIdentity` PASS；fingerprint 匹配 manifest |
| PRE-3 | manifest | `PROVISIONING_MANIFEST.txt` 含双方 version + fingerprint + signerKeyVersion |
| PRE-4 | M01 peer lookup | `pre4RemoteLookup` → Found(M02, V*) |

**EP4 裁决语义（已修正）：** `signerKeyVersion` 与 `establishmentKeyVersion` 数值相同 **允许**（各自独立 version space 的 generation #1）。EP4 PASS 看 domain independence（独立 store/API/authority/lookup + EP1/EP2/EP5 结构佐证），**不要求**人为分配不同整数（例如 M02=V4）。

---

## 4. 手工 adb 等价命令（调试）

### 4.1 M01 provision

```powershell
adb -s HTUBB21B09220661 shell am force-stop com.talkback.appprod
adb -s HTUBB21B09220661 shell am instrument -w -r `
    -e establishmentKeyVersion 1 `
    -e class com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#provisionLocalEstablishmentIdentity `
    com.talkback.appprod.test/androidx.test.runner.AndroidJUnitRunner
adb -s HTUBB21B09220661 shell "run-as com.talkback.appprod cat files/gbc-trust/EP_OPERATOR_EVIDENCE.txt"
```

### 4.2 M02 provision

将 serial 换为 `DSJ-2407041`，其余相同。

### 4.3 Authority（workstation Gradle）

```powershell
$env:EP_BATCH_DIR = "d:\workspace\project\talkback\talkback\logs\ep-operator-batch-<stamp>"
$env:EP_TASK_PROFILE_REVISION = "20"
cd d:\workspace\project\talkback\talkback
./gradlew :android-board-talkback:testDebugUnitTest `
    --tests "com.talkback.core.session.gbc.trust.profile.establishment.EstablishmentOperatorAuthorityFieldHarnessTest.runBatchFromEnv"
```

### 4.4 单台 accept（base64 instrumentation）

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("<BatchDir>\establishment-signed-M01.bin"))
adb -s HTUBB21B09220661 shell am instrument -w -r `
    -e signedDeliveryBase64 $b64 `
    -e class com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#writeSignedEstablishmentDeliveryFromBase64 `
    com.talkback.appprod.test/androidx.test.runner.AndroidJUnitRunner
adb -s HTUBB21B09220661 shell am instrument -w -r `
    -e class com.talkback.appprod.fieldtest.EpEstablishmentOperatorProvisionInstrumentedTest#acceptEstablishmentRevision `
    com.talkback.appprod.test/androidx.test.runner.AndroidJUnitRunner
```

> 不使用 `/sdcard/Download/`（field 设备常见 EACCES）。`ep-establishment-operator-accept-device.ps1` 已封装上述流程。

---

## 5. 失败停点矩阵

| 停点 | 症状 | 动作 |
|------|------|------|
| Step 1/2 | `PROVISION_EVIDENCE outcome=FAILED` | 查 Keystore provider / moduleId；**不改** production code |
| Step 3 authority | Gradle test FAIL | 查 evidence 文件完整性、fingerprint round-trip |
| Step 3 accept | `AuthenticationRejected` | anchor SPKI 与 `FieldTestOperationalAuthority` 不一致 → 重跑 FTPH |
| Step 3 accept | `SemanticRejected` | binding 缺一方 → 重跑 authority batch |
| PRE-2 | `FINGERPRINT_MISMATCH` | Keystore version 与 manifest 不一致 → 从 Step 1 重来 |
| PRE-4 | lookup 非 Found | store 缺 M02 binding → 重 accept；**不改** Factory |

---

## 6. EP Field Run #2（PRE PASS 后）

从 manifest 读取 `M02.establishmentKeyVersion`（EP1 lookup 用 peer version）：

```powershell
.\ep-establishment-field-collect.ps1 `
    -EstablishmentKeyVersion <M02.establishmentKeyVersion> `
    -M02Serial DSJ-2407041

.\ep-establishment-field-adjudicate.ps1 -LogDir <collect output dir>
```

```text
PASS  → establishment field gate CLOSED / FIELD PROVEN → P1-D authorized (see p1-d authorization doc)
FAIL  → 证据层分类（provisioning mismatch / version drift）
INVALID → instrumentation / log capture
```

---

## 7. Instrumentation 入口

| 方法 | 用途 |
|------|------|
| `provisionLocalEstablishmentIdentity` | Step 1/2 |
| `stageSignedEstablishmentDelivery` | push 到 `/sdcard/Download/` 后 stage |
| `acceptEstablishmentRevision` | PAP accept |
| `verifyLocalKeystoreIdentity` | PRE-2 |
| `pre4RemoteLookup` | PRE-4（M01 only） |

Probe 前缀：`EP_OP_PROBE`（operator）/ `EP_PRE_PROBE`（PRE-4 复用 EP1 逻辑）

---

## 8. 脚本索引

| 脚本 | 作用 |
|------|------|
| `ep-establishment-operator-provision-all.ps1` | 全流程编排 |
| `ep-establishment-operator-provision-device.ps1` | 单台 Keystore + evidence |
| `ep-establishment-operator-pull-evidence.ps1` | 拉取 evidence 到 batch |
| `ep-establishment-operator-authority-batch.ps1` | workstation 签名 + manifest |
| `ep-establishment-operator-accept-device.ps1` | 单台 PAP accept |
| `ep-establishment-operator-pre-check.ps1` | PRE-1..PRE-4 |
| `ep-establishment-field-collect.ps1` | EP Run #2 采集 |
| `ep-establishment-field-adjudicate.ps1` | EP 裁决 |
