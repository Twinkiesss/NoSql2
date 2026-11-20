param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [Parameter(Mandatory=$true)][string]$ObjectId,
    [int]$Requests = 500,
    [string]$Output = "scylla-benchmark.csv"
)

$pair = "$Username`:$Password"
$token = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $token" }
$url = "$BaseUrl/api/events/$ObjectId/views"
$latencies = [System.Collections.Generic.List[double]]::new()
$success = 0
$errors = 0
$total = [System.Diagnostics.Stopwatch]::StartNew()

for ($i = 0; $i -lt $Requests; $i++) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        Invoke-RestMethod -Method Post -Uri $url -Headers $headers -TimeoutSec 20 | Out-Null
        $sw.Stop()
        $latencies.Add($sw.Elapsed.TotalMilliseconds)
        $success++
    } catch {
        $sw.Stop()
        $errors++
        Write-Warning "Request $($i + 1) failed: $($_.Exception.Message)"
    }
}
$total.Stop()

$sorted = @($latencies | Sort-Object)
$average = if ($success -gt 0) { ($latencies | Measure-Object -Average).Average } else { 0 }
$p95 = if ($success -gt 0) {
    $index = [Math]::Ceiling(0.95 * $sorted.Count) - 1
    $sorted[[Math]::Max(0, $index)]
} else { 0 }
$throughput = if ($total.Elapsed.TotalSeconds -gt 0) { $success / $total.Elapsed.TotalSeconds } else { 0 }
$availability = if ($Requests -gt 0) { 100.0 * $success / $Requests } else { 0 }

$result = [pscustomobject]@{
    Timestamp = (Get-Date).ToString("s")
    Requests = $Requests
    Success = $success
    Errors = $errors
    DurationSeconds = [Math]::Round($total.Elapsed.TotalSeconds, 3)
    ThroughputOpsPerSecond = [Math]::Round($throughput, 3)
    AverageLatencyMs = [Math]::Round($average, 3)
    P95LatencyMs = [Math]::Round($p95, 3)
    AvailabilityPercent = [Math]::Round($availability, 2)
}
$result | Format-List
$result | Export-Csv -Path $Output -NoTypeInformation -Append -Encoding UTF8
Write-Host "Результат добавлен в $Output"
