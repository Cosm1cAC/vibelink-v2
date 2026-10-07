$ErrorActionPreference = 'Continue'
$py = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $py) { $py = 'C:\Python314\python.exe' }
& $py (Join-Path $PSScriptRoot 'server.py')
