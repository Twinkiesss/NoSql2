<#
.SYNOPSIS
  Сравнение QUORUM vs ALL для ScyllaDB.
  Write и read — ЧЕРЕЗ HTTP (Java), чтобы objectHash был одинаковым.
#>

param(
    [string]$BaseUrl    = "http://localhost:8081",
    [string]$Username   = "admin",
    [string]$Password   = "admin123",
    [string]$ObjectId   = "bench-object-001",
    [int]$WriteCount    = 200,
    [int]$ReadCount     = 100,
    [int]$Runs          = 3,
    [string]$Output     = "consistency-results.csv"
)

$ErrorActionPreference = "Stop"

function Wait-ClusterUp {
    param([int]$TimeoutSec = 120)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $status = docker exec library-scylla1 nodetool status 2>&1
        $unCount = ([regex]::Matches($status, "\bUN\b")).Count
        if ($unCount -ge 3) { return }
        Start-Sleep -Seconds 3
    }
    throw "Cluster not ready: expected 3 x UN within $TimeoutSec sec"
}

function Get-AuthHeader {
    $pair  = "$Username`:$Password"
    $token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    return @{ Authorization = "Basic $token" }
}

# ---------- 0. preflight ----------

Write-Host "=== Preflight ===" -ForegroundColor Cyan
Wait-ClusterUp
Write-Host "Cluster: 3 x UN - OK" -ForegroundColor Green

$headers = Get-AuthHeader
$health  = Invoke-RestMethod -Uri "$BaseUrl/api/action-history/health" -Headers $headers
if (-not $health.scyllaAvailable) { throw "Application says scyllaAvailable=false." }
Write-Host "App health: scyllaAvailable=true - OK" -ForegroundColor Green

# ---------- 1. read URL: вчера + сегодня (UTC) ----------

$utcFrom = (Get-Date).AddDays(-1).ToUniversalTime().ToString("yyyy-MM-dd")
$utcTo   = (Get-Date).ToUniversalTime().ToString("yyyy-MM-dd")
$readUrl = $BaseUrl + "/api/action-history/object/" + $ObjectId +
           "?from=" + $utcFrom + "T00:00:00Z&to=" + $utcTo + "T23:59:59Z&page=0&size=10"
$writeUrl = $BaseUrl + "/api/action-history/benchmark?objectId=" + $ObjectId + "&count=" + $WriteCount

Write-Host "Read URL : $readUrl"  -ForegroundColor DarkGray
Write-Host "Write URL: $writeUrl" -ForegroundColor DarkGray

# ---------- 2. warm-up ----------

$warm = [Math]::Max(1, [int]($ReadCount / 5))
Write-Host "=== Warm-up ($warm reads) ===" -ForegroundColor Cyan
for ($i = 0; $i -lt $warm; $i++) {
    try { Invoke-RestMethod -Method Get -Uri $readUrl -Headers $headers -TimeoutSec 10 | Out-Null } catch { }
}

# ---------- 3. run for one CL ----------

function Run-ForConsistency {
    param([string]$Consistency)

    Write-Host "=== Consistency: $Consistency ===" -ForegroundColor Yellow
    $results = @()

    for ($run = 1; $run -le $Runs; $run++) {
        Write-Host "  run $run/$Runs ..." -NoNewline

        # --- WRITE через HTTP (Java считает objectHash) ---
        $writeSw = [System.Diagnostics.Stopwatch]::StartNew()
        $writeResp = Invoke-RestMethod -Method Post -Uri $writeUrl -Headers $headers -TimeoutSec 60
        $writeSw.Stop()
        $writeSec = [Math]::Round($writeSw.Elapsed.TotalSeconds, 3)
        $writeThroughput = [Math]::Round($WriteCount / $writeSec, 2)

        # --- READ через HTTP ---
        $latencies = [System.Collections.Generic.List[double]]::new()
        $readSuccess = 0
        $readErrors  = 0
        $readSw = [System.Diagnostics.Stopwatch]::StartNew()
        for ($i = 0; $i -lt $ReadCount; $i++) {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                Invoke-RestMethod -Method Get -Uri $readUrl -Headers $headers -TimeoutSec 20 | Out-Null
                $sw.Stop()
                $latencies.Add($sw.Elapsed.TotalMilliseconds)
                $readSuccess++
            } catch {
                $sw.Stop()
                $readErrors++
            }
        }
        $readSw.Stop()
        $readSec = [Math]::Round($readSw.Elapsed.TotalSeconds, 3)

        $sorted = @($latencies | Sort-Object)
        $avg    = if ($readSuccess -gt 0) { ($latencies | Measure-Object -Average).Average } else { 0 }
        $p95    = if ($readSuccess -gt 0) {
            $idx = [Math]::Ceiling(0.95 * $sorted.Count) - 1
            $sorted[[Math]::Max(0, $idx)]
        } else { 0 }
        $readThroughput = [Math]::Round($readSuccess / $readSec, 2)

        $results += [pscustomobject]@{
            Timestamp         = (Get-Date).ToString("s")
            Consistency       = $Consistency
            Run               = $run
            WriteCount        = $WriteCount
            WriteElapsedSec   = $writeSec
            WriteThroughput   = $writeThroughput
            ReadCount         = $ReadCount
            ReadSuccess       = $readSuccess
            ReadErrors        = $readErrors
            ReadElapsedSec    = $readSec
            ReadThroughput    = $readThroughput
            AvgLatencyMs      = [Math]::Round($avg, 3)
            P95LatencyMs      = [Math]::Round($p95, 3)
            AvailabilityPct   = [Math]::Round(100.0 * $readSuccess / $ReadCount, 2)
        }

        Write-Host " done (W=$writeThroughput/s, R=$readThroughput/s, p95=$([Math]::Round($p95,1))ms, avail=$([Math]::Round(100.0 * $readSuccess / $ReadCount,0))%)" -ForegroundColor Gray
    }

    return $results
}

# ---------- 4. основной поток ----------

$all = @()
$all += Run-ForConsistency -Consistency "QUORUM"

Write-Host ""
Write-Host "!!! Now restart the app container with SCYLLA_CONSISTENCY=ALL !!!" -ForegroundColor Magenta
Write-Host "    1) В docker-compose.yml поменяйте SCYLLA_CONSISTENCY: QUORUM -> ALL"
Write-Host "    2) docker compose up -d app"
Write-Host "    3) Дождитесь 'Started LibraryEventApplication' в docker logs -f library-app"
Write-Host "    4) Нажмите Enter здесь..." -ForegroundColor Magenta
Read-Host | Out-Null

$deadline = (Get-Date).AddSeconds(180)
while ((Get-Date) -lt $deadline) {
    try {
        $h = Invoke-RestMethod -Uri "$BaseUrl/api/action-history/health" -Headers $headers -TimeoutSec 5
        if ($h.scyllaAvailable) { break }
    } catch { Start-Sleep -Seconds 3 }
}

$all += Run-ForConsistency -Consistency "ALL"

# ---------- 5. CSV ----------

$all | Export-Csv -Path $Output -NoTypeInformation -Encoding UTF8
Write-Host ""
Write-Host "=== Results written to $Output ===" -ForegroundColor Green
$all | Format-Table Consistency, Run, WriteThroughput, ReadThroughput, AvgLatencyMs, P95LatencyMs, AvailabilityPct -AutoSize