<#
.SYNOPSIS
  Эксперимент B: отказ узла scylla3 + hinted handoff.
.DESCRIPTION
  - Требует SCYLLA_CONSISTENCY=QUORUM.
  - Опционально TRUNCATE трёх денормализованных таблиц перед прогоном.
  - Baseline: nodetool status + COUNT(*) на каждом узле + netstats.
  - stop scylla3, ждёт DN по IP.
  - Пишет WriteCount событий через /api/action-history/benchmark (QUORUM).
  - Фиксирует hints (system.hints + netstats + tpstats).
  - start scylla3, ждёт UN и готовность CQL (с запасом по времени).
  - Ждёт дренаж hints (system.hints -> 0) с таймаутом.
  - Снимает COUNT(*) на всех узлах, сравнивает, пишет counts + timings.
  - Get-NodeCount не падает, если CQL не готов: возвращает -1.
  - StrictHintCheck (опционально): sync point через REST API изнутри контейнера.
#>

param(
    [string]$BaseUrl    = "http://localhost:8081",
    [string]$Username   = "admin",
    [string]$Password   = "admin123",
    [string]$ObjectId   = "failover-object-001",
    [int]$WriteCount    = 1000,
    [switch]$TruncateBefore = $false,
    [switch]$StrictHintCheck = $false,
    [int]$WaitHintsSec  = 30,
    [int]$WaitNodeSec   = 60,
    [int]$WaitCqlSec    = 90,
    [int]$WaitDnSec     = 30,
    [string]$LogFile    = "failover-log.txt",
    [string]$CountsFile = "failover-counts.txt",
    [string]$TimingsFile= "failover-timings.txt"
)

$ErrorActionPreference = "Stop"

function Get-AuthHeader {
    $pair  = "$Username`:$Password"
    $token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    return @{ Authorization = "Basic $token" }
}

function Log {
    param([string]$msg)
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $msg"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

# ---------- helpers ----------

function Get-NodeCount {
    param([string]$Container)
    # Не падаем, если cqlsh не готов: возвращаем -1.
    try {
        $out = docker exec $Container cqlsh --no-color -e "SELECT count(*) FROM library_events.action_by_object_bucket;" 2>&1 | Out-String
        $m = [regex]::Match($out, '(?ms)^-+\s*\r?\n\s*(\d+)\s*\r?\n')
        if ($m.Success) { return [int]$m.Groups[1].Value }
        $nums = [regex]::Matches($out, '\b(\d+)\b') | ForEach-Object { [int]$_.Groups[1].Value }
        if ($nums.Count -gt 0) { return $nums[-1] }
    } catch {
        Log "  (Get-NodeCount $Container failed: $($_.Exception.Message))"
    }
    return -1
}

function Get-StatusRaw {
    param([string]$Container = "library-scylla1")
    return (docker exec $Container nodetool status 2>&1 | Out-String)
}

function Get-UnCount {
    param([string]$StatusOut)
    return ([regex]::Matches($StatusOut, '(?m)^\s*UN\s')).Count
}

function Get-Scylla3Ip {
    $ip = (docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' library-scylla3 2>$null | Out-String).Trim()
    return $ip
}

function Wait-Scylla3Dn {
    param([int]$TimeoutSec = 30)
    $ip3 = Get-Scylla3Ip
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $s = Get-StatusRaw
        if ($ip3 -and $s -match "(?m)^\s*DN\s+$([regex]::Escape($ip3))\b") { return $true }
        if ((Get-UnCount $s) -lt 3) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Wait-AllUn {
    param([int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $s = Get-StatusRaw
        if ((Get-UnCount $s) -ge 3) { return $true }
        Start-Sleep -Seconds 3
    }
    return $false
}

function Wait-CqlReady {
    param([string]$Container, [int]$TimeoutSec = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $out = docker exec $Container cqlsh --no-color -e "SELECT now() FROM system.local;" 2>&1 | Out-String
            if ($out -match '\d{4}-\d{2}-\d{2}') { return $true }
        } catch {}
        Start-Sleep -Seconds 3
    }
    return $false
}

function Get-HintsCount {
    param([string]$Container)
    try {
        $out = docker exec $Container cqlsh --no-color -e "SELECT count(*) FROM system.hints;" 2>&1 | Out-String
        $m = [regex]::Match($out, '(?ms)^-+\s*\r?\n\s*(\d+)\s*\r?\n')
        if ($m.Success) { return [int]$m.Groups[1].Value }
    } catch {}
    try {
        $ns = docker exec $Container nodetool netstats 2>&1 | Out-String
        $h  = 0
        foreach ($line in $ns -split "`n") {
            if ($line -match 'Hints\s+in\s+progress\s*:\s*(\d+)') { $h += [int]$Matches[1] }
            elseif ($line -match '^\s*Hints\s*:\s*(\d+)')          { $h += [int]$Matches[1] }
        }
        return $h
    } catch {}
    return -1
}

function Get-HintsTpstats {
    param([string]$Container)
    $out = docker exec $Container nodetool tpstats 2>&1 | Out-String
    ($out -split "`n" | Where-Object { $_ -match '(?i)hint' }) -join "`n"
}

function Get-Netstats {
    param([string]$Container)
    docker exec $Container nodetool netstats 2>&1 | Out-String
}

function Ensure-ContainerRunning {
    param([string]$Name, [int]$TimeoutSec = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $st = (docker inspect -f '{{.State.Status}}' $Name 2>$null)
        if ($st -and $st.Trim() -eq "running") { return $true }
        Start-Sleep -Seconds 2
    }
    return $false
}

# ---------- Scylla REST API (sync point), запросы изнутри scylla1 ----------

function Invoke-ScyllaSyncPointCreate {
    param(
        [string]$FromContainer = "library-scylla1",
        [string]$TargetIp
    )
    # curl есть в образе Scylla; ходим изнутри контейнера, чтобы не зависеть от проброса порта 10000.
    try {
        $raw = docker exec $FromContainer curl -s -X POST "http://127.0.0.1:10000/hinted_handoff/sync_point?target_hosts=$TargetIp" 2>&1 | Out-String
        return $raw.Trim()
    } catch {
        return "ERROR: $($_.Exception.Message)"
    }
}

function Invoke-ScyllaSyncPointStatus {
    param(
        [string]$FromContainer = "library-scylla1",
        [string]$SyncPointId
    )
    try {
        $raw = docker exec $FromContainer curl -s "http://127.0.0.1:10000/hinted_handoff/sync_point?id=$SyncPointId&timeout=10" 2>&1 | Out-String
        return $raw.Trim()
    } catch {
        return "ERROR: $($_.Exception.Message)"
    }
}

# ---------- 0. preflight ----------

foreach ($f in @($LogFile,$CountsFile,$TimingsFile)) { if (Test-Path $f) { Remove-Item $f } }

Log "=== Preflight ==="
$status = Get-StatusRaw
$un = Get-UnCount $status
if ($un -lt 3) { throw "Cluster not ready: expected 3 x UN, got $un.`n$status" }
Log "Cluster: 3 x UN - OK"

$headers = Get-AuthHeader
try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/api/action-history/health" -Headers $headers -TimeoutSec 15
} catch {
    throw "Health check failed: $($_.Exception.Message)"
}
if (-not $health.scyllaAvailable) { throw "App: scyllaAvailable=false" }
Log "App: scyllaAvailable=true - OK"

$consistency = (docker exec library-app printenv SCYLLA_CONSISTENCY 2>&1 | Out-String).Trim()
Log "App consistency: $consistency"
if ($consistency -ne "QUORUM") {
    throw "Experiment requires SCYLLA_CONSISTENCY=QUORUM, got '$consistency'."
}

# ---------- 0.5 optional TRUNCATE ----------

if ($TruncateBefore) {
    Log "=== Truncating denormalized tables ==="
    foreach ($t in @("action_by_object_bucket","action_by_user_bucket","action_by_type_bucket")) {
        docker exec library-scylla1 cqlsh --no-color -e "TRUNCATE library_events.$t;" 2>&1 | Out-Null
        Log "TRUNCATE library_events.$t"
    }
    Start-Sleep -Seconds 3
    Log "Tables truncated"
}

# ---------- 1. baseline ----------

Log "=== Baseline ==="
$c1 = Get-NodeCount "library-scylla1"
$c2 = Get-NodeCount "library-scylla2"
$c3 = Get-NodeCount "library-scylla3"
Log "COUNT(*) scylla1=$c1 scylla2=$c2 scylla3=$c3"
Add-Content $CountsFile "BEFORE scylla1=$c1 scylla2=$c2 scylla3=$c3" -Encoding UTF8

Log "--- netstats BEFORE (scylla1) ---"
Get-Netstats "library-scylla1" | ForEach-Object { Log $_ }

# ---------- 2. stop scylla3 ----------

Log "=== Stopping library-scylla3 ==="
$tStop = Get-Date
$ip3 = Get-Scylla3Ip
Log "scylla3 IP: $ip3"
docker stop library-scylla3 | Out-Null

Log "Waiting for scylla3 to become DN (timeout ${WaitDnSec}s)..."
if (-not (Wait-Scylla3Dn -TimeoutSec $WaitDnSec)) {
    Log "WARNING: scylla3 still not DN, продолжаем"
    Log "--- nodetool status (raw) ---"
    Get-StatusRaw | ForEach-Object { Log $_ }
} else {
    Log "scylla3 is DN - OK"
}
$tDn = Get-Date

# ---------- 3. write with QUORUM ----------

Log "=== Writing $WriteCount events with QUORUM (scylla3 down) ==="
$writeUrl = "$BaseUrl/api/action-history/benchmark?objectId=$ObjectId&count=$WriteCount"
$tWriteStart = Get-Date
try {
    $resp = Invoke-RestMethod -Method Post -Uri $writeUrl -Headers $headers -TimeoutSec 300
    $tWriteEnd = Get-Date
    Log "Benchmark OK: written=$($resp.written), elapsedMs=$($resp.elapsedMs), throughput=$($resp.throughputOpsPerSec)/s"
} catch {
    $tWriteEnd = Get-Date
    Log "Benchmark FAILED: $($_.Exception.Message)"
    throw
}

# ---------- 4. hints (during down) ----------

Log "=== Hints on live nodes (scylla3 down) ==="
Start-Sleep -Seconds 3
$h1 = Get-HintsCount "library-scylla1"
$h2 = Get-HintsCount "library-scylla2"
Log "system.hints count: scylla1=$h1 scylla2=$h2"
Add-Content $CountsFile "HINTS_DURING scylla1=$h1 scylla2=$h2" -Encoding UTF8

Log "--- netstats DURING (scylla1) ---"
Get-Netstats "library-scylla1" | ForEach-Object { Log $_ }

Log "--- tpstats hints (scylla1) ---"
Get-HintsTpstats "library-scylla1" | ForEach-Object { if ($_) { Log $_ } }

# ---------- 4.5 optional: sync point (strict) ----------

$syncPointId = $null
if ($StrictHintCheck) {
    Log "=== StrictHintCheck: creating sync point for $ip3 ==="
    $syncPointId = Invoke-ScyllaSyncPointCreate -FromContainer "library-scylla1" -TargetIp $ip3
    Log "sync point id: $syncPointId"
    Add-Content $CountsFile "SYNC_POINT_CREATE id=$syncPointId" -Encoding UTF8
    $spStatus = Invoke-ScyllaSyncPointStatus -FromContainer "library-scylla1" -SyncPointId $syncPointId
    Log "sync point status (during down): $spStatus"
    Add-Content $CountsFile "SYNC_POINT_DURING $spStatus" -Encoding UTF8
}

# ---------- 5. start scylla3 ----------

Log "=== Starting library-scylla3 ==="
$tStart = Get-Date
docker start library-scylla3 | Out-Null

if (-not (Ensure-ContainerRunning "library-scylla3" -TimeoutSec $WaitNodeSec)) {
    Log "--- docker logs scylla3 (last 40) ---"
    docker logs --tail 40 library-scylla3 2>&1 | ForEach-Object { Log $_ }
    throw "scylla3 container did not reach 'running' state"
}
Log "Container scylla3 is running"

Log "Waiting for scylla3 to become UN (timeout ${WaitNodeSec}s)..."
if (-not (Wait-AllUn -TimeoutSec $WaitNodeSec)) {
    Log "WARNING: scylla3 didn't become UN in ${WaitNodeSec}s"
} else {
    Log "scylla3 is UN - OK"
}
$tUn = Get-Date

Log "Waiting for CQL on scylla3 (timeout ${WaitCqlSec}s)..."
if (-not (Wait-CqlReady "library-scylla3" -TimeoutSec $WaitCqlSec)) {
    Log "WARNING: cqlsh on scylla3 not ready in ${WaitCqlSec}s, пробуем всё равно"
} else {
    Log "CQL on scylla3 ready - OK"
}

# ---------- 6. wait hints -> 0 ----------

Log "=== Waiting for hints to drain (max ${WaitHintsSec}s) ==="
$tDrainStart = Get-Date
$deadline = (Get-Date).AddSeconds($WaitHintsSec)
$drained = $false
$lastH1 = -1; $lastH2 = -1
while ((Get-Date) -lt $deadline) {
    $h1 = Get-HintsCount "library-scylla1"
    $h2 = Get-HintsCount "library-scylla2"
    if ($h1 -ne $lastH1 -or $h2 -ne $lastH2) {
        Log "hints: scylla1=$h1 scylla2=$h2"
        $lastH1 = $h1; $lastH2 = $h2
    }
    if ($h1 -eq 0 -and $h2 -eq 0) { $drained = $true; break }
    Start-Sleep -Seconds 3
}
$tDrainEnd = Get-Date
if ($drained) { Log "Hints drained - OK" }
else { Log "WARNING: hints still > 0 after ${WaitHintsSec}s" }

Log "--- netstats AFTER drain (scylla1) ---"
Get-Netstats "library-scylla1" | ForEach-Object { Log $_ }

if ($StrictHintCheck -and $syncPointId) {
    Log "=== StrictHintCheck: final sync point status ==="
    $spFinal = Invoke-ScyllaSyncPointStatus -FromContainer "library-scylla1" -SyncPointId $syncPointId
    Log "sync point final: $spFinal"
    Add-Content $CountsFile "SYNC_POINT_FINAL $spFinal" -Encoding UTF8
}

# ---------- 7. final counts ----------

Log "=== Final counts ==="
$c1 = Get-NodeCount "library-scylla1"
$c2 = Get-NodeCount "library-scylla2"
$c3 = Get-NodeCount "library-scylla3"
Log "COUNT(*) scylla1=$c1 scylla2=$c2 scylla3=$c3"
Add-Content $CountsFile "AFTER scylla1=$c1 scylla2=$c2 scylla3=$c3" -Encoding UTF8

# ---------- 8. timings ----------

$timings = @(
    "t_stop            = $($tStop.ToString('yyyy-MM-dd HH:mm:ss'))",
    "t_dn              = $($tDn.ToString('yyyy-MM-dd HH:mm:ss'))",
    "t_write_start     = $($tWriteStart.ToString('yyyy-MM-dd HH:mm:ss'))",
    "t_write_end       = $($tWriteEnd.ToString('yyyy-MM-dd HH:mm:ss'))",
    "t_start           = $($tStart.ToString('yyyy-MM-dd HH:mm:ss'))",
    "t_un              = $($tUn.ToString('yyyy-MM-dd HH:mm:ss'))",
    "t_drain_start     = $($tDrainStart.ToString('yyyy-MM-dd HH:mm:ss'))",
    "t_drain_end       = $($tDrainEnd.ToString('yyyy-MM-dd HH:mm:ss'))",
    "duration_down_sec = $([math]::Round(($tStart - $tStop).TotalSeconds,1))",
    "duration_drain_sec= $([math]::Round(($tDrainEnd - $tDrainStart).TotalSeconds,1))"
)
$timings | ForEach-Object { Log $_; Add-Content $TimingsFile $_ -Encoding UTF8 }

# ---------- 9. verdict ----------

Log "=== Result ==="
$expected = $c1 + $WriteCount
if ($c3 -eq -1) {
    Log "MISMATCH/ERROR: scylla3 COUNT(*) недоступен (CQL не поднялся)."
    Log "Проверь вручную: docker exec library-scylla3 cqlsh -e \"SELECT count(*) FROM library_events.action_by_object_bucket;\""
} elseif ($c1 -eq $c3 -and $c2 -eq $c3) {
    Log "OK: все узлы имеют одинаковое число событий ($c3). Hinted handoff сработал."
} else {
    Log "MISMATCH: scylla1=$c1, scylla2=$c2, scylla3=$c3 (ожидалось ~$expected на всех)"
    Log "Hinted handoff не догнал. Возможно, нужен nodetool repair."
}