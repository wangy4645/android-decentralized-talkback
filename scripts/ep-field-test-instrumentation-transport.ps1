# Field-test instrumentation result transport (Option A) — parse/write helpers.
# Contract: FieldTestInstrumentationTransport.kt (androidTest)

param([switch]$SelfTest)

$Script:FieldTestStoreArtifactEstablishment = "accepted-local-establishment-trust-state.bin"
$Script:FieldTestStoreArtifactSigning = "accepted-local-trust-state.bin"

function Get-FieldTestTransportFromInstrumentOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstrumentOutput,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedKind
    )

    $kind = $null
    $payloadB64 = $null
    foreach ($line in ($InstrumentOutput -split "`r?`n")) {
        if ($line -match '^INSTRUMENTATION_(RESULT|STATUS):\s*transportKind=(.+)$') {
            $kind = $Matches[2].Trim()
        } elseif ($line -match '^INSTRUMENTATION_(RESULT|STATUS):\s*payloadBase64=(.+)$') {
            $payloadB64 = $Matches[2].Trim()
        }
    }

    if ([string]::IsNullOrWhiteSpace($kind) -or [string]::IsNullOrWhiteSpace($payloadB64)) {
        throw "transport incomplete: missing transportKind or payloadBase64"
    }
    if ($kind -ne $ExpectedKind) {
        throw "transport kind mismatch: expected=$ExpectedKind actual=$kind"
    }

    try {
        $bytes = [Convert]::FromBase64String($payloadB64)
    } catch {
        throw "transport payloadBase64 invalid: $($_.Exception.Message)"
    }
    if ($bytes.Length -eq 0) {
        throw "transport payload decoded empty"
    }

    return [pscustomobject]@{
        Kind = $kind
        PayloadUtf8 = [Text.Encoding]::UTF8.GetString($bytes)
        PayloadBase64 = $payloadB64
    }
}

function Write-FieldTestTransportPayloadFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstrumentOutput,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedKind,
        [Parameter(Mandatory = $true)]
        [string]$OutPath
    )

    $transport = Get-FieldTestTransportFromInstrumentOutput -InstrumentOutput $InstrumentOutput -ExpectedKind $ExpectedKind
    $dir = Split-Path $OutPath -Parent
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    [System.IO.File]::WriteAllText($OutPath, $transport.PayloadUtf8 + "`n")
    return $transport
}

function Test-FieldTestStoreSanityFromInstrumentOutput {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InstrumentOutput,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedArtifact
    )

    $transport = Get-FieldTestTransportFromInstrumentOutput `
        -InstrumentOutput $InstrumentOutput `
        -ExpectedKind "STORE_SANITY"
    $artifact = $null
    $present = $null
    foreach ($line in ($transport.PayloadUtf8 -split "`r?`n")) {
        if ($line -match '^artifact=(.+)$') { $artifact = $Matches[1].Trim() }
        if ($line -match '^present=(true|false)$') { $present = ($Matches[1].Trim() -eq 'true') }
    }
    if ($artifact -ne $ExpectedArtifact) {
        throw "store sanity artifact mismatch: expected=$ExpectedArtifact actual=$artifact"
    }
    if ($null -eq $present) {
        throw "store sanity missing present="
    }
    return [pscustomobject]@{
        Artifact = $artifact
        Present = $present
    }
}

function Invoke-FieldTestStoreSanityProbe {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Serial,
        [Parameter(Mandatory = $true)]
        [string]$ExpectedArtifact,
        [Parameter(Mandatory = $true)]
        [string]$TestMethod,
        [string]$PackageName = "com.talkback.appprod"
    )

    adb -s $Serial shell am force-stop $PackageName | Out-Null
    $instrumentOut = adb -s $Serial shell am instrument -w -r `
        -e class $TestMethod `
        "$PackageName.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "store sanity instrument failed exit=$LASTEXITCODE"
    }
    return Test-FieldTestStoreSanityFromInstrumentOutput `
        -InstrumentOutput $instrumentOut `
        -ExpectedArtifact $ExpectedArtifact
}

function Invoke-FieldTestInstrumentationTransportSelfTest {
    $failures = @()

    function Assert-Fail([string]$Name, [scriptblock]$Block) {
        try {
            & $Block
            $script:failures += "$Name (expected throw)"
        } catch {
            # expected
        }
    }

    function Assert-Pass([string]$Name, [scriptblock]$Block) {
        try {
            & $Block
        } catch {
            $script:failures += "$Name ($($_.Exception.Message))"
        }
    }

    $sampleEvidence = @(
        "moduleId=M01"
        "algorithm=RSA_3072_OAEP_SHA256_MGF1_SHA1"
        "publicKeyFingerprintSha256Hex=abc"
        "keyAlias=talkback-establishment-M01-v1"
        "localKeyGeneration=1"
        "publicKeySpkiBase64=QUJD"
        "operatorAssignedEstablishmentKeyVersion=1"
    ) -join "`n"
    $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($sampleEvidence))
    $sampleOut = @(
        "INSTRUMENTATION_STATUS: transportKind=EP_OPERATOR_EVIDENCE"
        "INSTRUMENTATION_STATUS: payloadBase64=$b64"
    ) -join "`n"

    Assert-Pass "roundtrip_evidence" {
        $t = Get-FieldTestTransportFromInstrumentOutput -InstrumentOutput $sampleOut -ExpectedKind "EP_OPERATOR_EVIDENCE"
        if ($t.PayloadUtf8 -ne $sampleEvidence) { throw "payload mismatch" }
    }

    Assert-Fail "missing_kind" {
        Get-FieldTestTransportFromInstrumentOutput -InstrumentOutput "INSTRUMENTATION_STATUS: payloadBase64=$b64" -ExpectedKind "EP_OPERATOR_EVIDENCE"
    }

    Assert-Fail "wrong_kind" {
        Get-FieldTestTransportFromInstrumentOutput -InstrumentOutput $sampleOut -ExpectedKind "FTPH_RESULT"
    }

    Assert-Fail "bad_b64" {
        Get-FieldTestTransportFromInstrumentOutput -InstrumentOutput @(
            "INSTRUMENTATION_STATUS: transportKind=EP_OPERATOR_EVIDENCE"
            "INSTRUMENTATION_STATUS: payloadBase64=***"
        ) -join "`n" -ExpectedKind "EP_OPERATOR_EVIDENCE"
    }

    $storeOut = @(
        "INSTRUMENTATION_STATUS: transportKind=STORE_SANITY"
        "INSTRUMENTATION_STATUS: payloadBase64=$([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("artifact=accepted-local-establishment-trust-state.bin`npresent=true")))"
    ) -join "`n"
    Assert-Pass "store_present" {
        $s = Test-FieldTestStoreSanityFromInstrumentOutput -InstrumentOutput $storeOut -ExpectedArtifact "accepted-local-establishment-trust-state.bin"
        if (-not $s.Present) { throw "expected present=true" }
    }

    Assert-Fail "store_wrong_artifact" {
        Test-FieldTestStoreSanityFromInstrumentOutput -InstrumentOutput $storeOut -ExpectedArtifact "other.bin"
    }

    $signingStoreOut = @(
        "INSTRUMENTATION_STATUS: transportKind=STORE_SANITY"
        "INSTRUMENTATION_STATUS: payloadBase64=$([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("artifact=accepted-local-trust-state.bin`npresent=true")))"
    ) -join "`n"
    Assert-Pass "signing_store_present" {
        $s = Test-FieldTestStoreSanityFromInstrumentOutput `
            -InstrumentOutput $signingStoreOut `
            -ExpectedArtifact $Script:FieldTestStoreArtifactSigning
        if (-not $s.Present) { throw "expected present=true" }
    }

    Assert-Fail "signing_store_absent_flag" {
        $absentOut = @(
            "INSTRUMENTATION_STATUS: transportKind=STORE_SANITY"
            "INSTRUMENTATION_STATUS: payloadBase64=$([Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("artifact=accepted-local-trust-state.bin`npresent=false")))"
        ) -join "`n"
        $s = Test-FieldTestStoreSanityFromInstrumentOutput `
            -InstrumentOutput $absentOut `
            -ExpectedArtifact $Script:FieldTestStoreArtifactSigning
        if ($s.Present) { throw "expected present=false" }
    }

    if ($failures.Count -gt 0) {
        throw ("selftest FAIL:`n" + ($failures -join "`n"))
    }
    return "selftest PASS"
}

if ($SelfTest) {
    $result = Invoke-FieldTestInstrumentationTransportSelfTest
    Write-Host $result
}
