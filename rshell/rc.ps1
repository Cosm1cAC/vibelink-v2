param(
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Command
)

$BASE = Join-Path $env:TEMP 'rshell'
$id = [guid]::NewGuid().ToString('N').Substring(0, 8)
$run = Join-Path $BASE 'run.txt'
Set-Content -LiteralPath $run -Value ($id + "`n" + ($Command -join ' ')) -Encoding utf8
$result = Join-Path $BASE "result-$id.txt"
for ($i = 0; $i -lt 240; $i++) {
    Start-Sleep -Milliseconds 500
    if (Test-Path -LiteralPath $result) { break }
}
if (Test-Path -LiteralPath $result) {
    Get-Content -LiteralPath $result -Raw
} else {
    '[no result - timeout]'
}
