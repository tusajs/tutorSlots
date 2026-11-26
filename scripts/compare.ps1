# Compares C++ and Kotlin reports for every valid UTC offset (NFR-07, AC-23).
# Run from the repository root after building both parts:
#   cmake -S core-cpp -B build; cmake --build build --config Debug
#   cd app-kotlin; .\gradlew.bat fatJar; cd ..
#   powershell -ExecutionPolicy Bypass -File scripts\compare.ps1

param(
    [string]$Cpp = "build\Debug\tutor_slots.exe",
    [string]$Jar = "app-kotlin\build\libs\tutor-slots-1.0-all.jar",
    [string]$Snapshot = "samples\snapshot.tsv"
)

$tmp = Join-Path $env:TEMP "tutorslots-compare"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$diffs = 0
$count = 0

for ($m = -720; $m -le 840; $m += 15) {
    $sign = if ($m -lt 0) { "-" } else { "+" }
    $abs = [Math]::Abs($m)
    $offset = "{0}{1:D2}:{2:D2}" -f $sign, [Math]::Floor($abs / 60), ($abs % 60)

    & $Cpp $Snapshot --offset $offset --out "$tmp\cpp.txt"
    & java -jar $Jar report $Snapshot --offset $offset --out "$tmp\kt.txt"

    $a = [IO.File]::ReadAllBytes("$tmp\cpp.txt")
    $b = [IO.File]::ReadAllBytes("$tmp\kt.txt")
    if ([Convert]::ToBase64String($a) -ne [Convert]::ToBase64String($b)) {
        Write-Host "DIFF at $offset" -ForegroundColor Red
        $diffs++
    }
    $count++
}

if ($diffs -eq 0) {
    Write-Host "OK: $count offsets, reports are byte-identical" -ForegroundColor Green
} else {
    Write-Host "FAILED: $diffs of $count offsets differ" -ForegroundColor Red
    exit 1
}
