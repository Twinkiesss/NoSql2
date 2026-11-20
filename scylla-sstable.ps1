<#
.SYNOPSIS
  Эксперимент C: структура и содержимое SSTable.
.DESCRIPTION
  - nodetool flush таблицы -> гарантированно свежий SSTable на диске.
  - Находит каталог таблицы и "живой" SSTable (не snapshot).
  - Список компонентов SSTable (Data/Index/Summary/Filter/Statistics/CompressionInfo/TOC/Scylla/Digest).
  - scylla sstable dump-data -> JSON с partition key, clustering, cells.
  - scylla sstable dump-statistics -> метаданные (типы ключей, колонки).
  - nodetool tablestats -> статистика таблицы.
  - Всё пишет в sstable-output.txt (UTF-8 без BOM).
#>

param(
    [string]$Container   = "library-scylla1",
    [string]$Keyspace    = "library_events",
    [string]$Table       = "action_by_object_bucket",
    [string]$DataRoot    = "/var/lib/scylla/data",
    [string]$OutFile     = "sstable-output.txt",
    [int]$MaxDumpChars   = 8000
)

$ErrorActionPreference = "Stop"

function Log {
    param([string]$msg)
    Write-Host $msg
    [System.IO.File]::AppendAllText($OutFile, $msg + [Environment]::NewLine, [System.Text.Encoding]::UTF8)
}

function Log-Section {
    param([string]$title)
    Log ""
    Log ("=" * 78)
    Log "  $title"
    Log ("=" * 78)
}

function Invoke-Docker {
    param([string[]]$DockerArgs)
    $out = & docker @DockerArgs 2>&1 | Out-String
    return $out
}

if (Test-Path $OutFile) { Remove-Item $OutFile }

# ---------- 0. flush ----------

Log-Section "0. nodetool flush $Keyspace $Table"
$flushOut = Invoke-Docker @("exec", $Container, "nodetool", "flush", $Keyspace, $Table)
$flushOut -split "`n" | ForEach-Object { if ($_.Trim()) { Log $_ } }
Log "flush done"

# ---------- 1. каталог таблицы ----------

Log-Section "1. Каталог таблицы"
$tableDir = (Invoke-Docker @("exec", $Container, "bash", "-c", "ls -d $DataRoot/$Keyspace/$Table-* 2>/dev/null | head -1")).Trim()
if (-not $tableDir) { throw "Table directory not found for $Keyspace.$Table" }
Log "table dir: $tableDir"

# ---------- 2. компоненты SSTable ----------

Log-Section "2. Компоненты SSTable"
$lsOut = Invoke-Docker @("exec", $Container, "bash", "-c", "ls -la $tableDir/")
$lsOut -split "`n" | Where-Object { $_ -match '(-Data|-Index|-Summary|-Filter|-Statistics|-CompressionInfo|-TOC|-Scylla|-Digest)' } | ForEach-Object { Log $_ }

# ---------- 3. "живой" SSTable (не snapshot) ----------

Log-Section "3. Живой SSTable (maxdepth 1, не snapshot)"
$dataFile = (Invoke-Docker @("exec", $Container, "bash", "-c", "find $tableDir -maxdepth 1 -name '*-Data.db' -type f | head -1")).Trim()
if (-not $dataFile) { throw "No live *-Data.db found in $tableDir" }
Log "Data.db: $dataFile"

# ---------- 4. dump-data ----------

Log-Section "4. scylla sstable dump-data (partition key, clustering, cells)"
$dumpCmd = "scylla sstable dump-data --keyspace $Keyspace --table $Table --sstable $dataFile"
$dumpOut = Invoke-Docker @("exec", $Container, "bash", "-c", $dumpCmd)
$trimmed = $dumpOut
if ($trimmed.Length -gt $MaxDumpChars) { $trimmed = $trimmed.Substring(0, $MaxDumpChars) + "`n...[truncated]" }
$trimmed -split "`n" | ForEach-Object { Log $_ }

# ---------- 5. dump-statistics ----------

Log-Section "5. scylla sstable dump-statistics (типы ключей и колонок)"
$statCmd = "scylla sstable dump-statistics --keyspace $Keyspace --table $Table --sstable $dataFile"
$statOut = Invoke-Docker @("exec", $Container, "bash", "-c", $statCmd)
$statTrim = $statOut
if ($statTrim.Length -gt $MaxDumpChars) { $statTrim = $statTrim.Substring(0, $MaxDumpChars) + "`n...[truncated]" }
$statTrim -split "`n" | ForEach-Object { Log $_ }

# ---------- 6. nodetool tablestats ----------

Log-Section "6. nodetool tablestats $Keyspace.$Table"
$tsOut = Invoke-Docker @("exec", $Container, "nodetool", "tablestats", "$Keyspace.$Table")
$tsOut -split "`n" | ForEach-Object { Log $_ }

# ---------- 7. итог ----------

Log-Section "7. Итог"
$summary = @'
Компоненты SSTable: Data / Index / Summary / Filter / Statistics / CompressionInfo / TOC / Scylla / Digest.
Partition key: bucket_date + object_hash + object_id.
  dump-statistics: pk_type_name = CompositeType(SimpleDateType, Int32Type, UTF8Type).
  dump-data: value = "2026-11-20:14:failover-object-001".
Clustering: event_time DESC, event_id ASC.
  dump-statistics: clustering_key_types_names = [ReversedType(TimestampType), UTF8Type].
  Строки в dump-data идут по убыванию event_time (12:05:25.282 -> 12:05:25.280 -> ...).
SSTable immutable: при новых INSERT/flush появится новый *-Data.db, старый не меняется.
Tombstone: DELETE -> tombstone-маркер в dump-data; compaction уберёт.
'@
$summary -split "`n" | ForEach-Object { if ($_.Trim()) { Log $_ } }
Log "Выход: $OutFile"