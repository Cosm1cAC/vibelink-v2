<# Development dispatcher: official Codex CLI delegates work to a bound DeepSeek Bot. #>
param(
    [Parameter(Mandatory)][string]$BotId,
    [Parameter(Mandatory)][string]$PromptFile,
    [string]$Hub = 'http://127.0.0.1:18995',
    [string]$DataRoot = $(if ($env:VIBELINK_DATA) { $env:VIBELINK_DATA } else { Join-Path $env:LOCALAPPDATA 'vibelink-mesh/data' }),
    [int]$TimeoutSeconds = 900
)
$ErrorActionPreference = 'Stop'
$tokenFile = Join-Path $DataRoot 'dev/token.txt'
$headers = @{ 'x-mesh-token' = (Get-Content -LiteralPath $tokenFile -Raw).Trim() }
$bot = Invoke-RestMethod -Uri "$Hub/api/bots/$([uri]::EscapeDataString($BotId))" -Headers $headers
if ($bot.bot) { $bot = $bot.bot }
if ($bot.engine -ne 'codex' -or $bot.model -ne 'auto/deepseek-v4.1-flash') { throw 'Dispatch target must be a DeepSeek Bot' }
$prompt = Get-Content -LiteralPath $PromptFile -Raw -Encoding utf8
$body = @{ agent = 'codex'; botId = $BotId; cwd = $bot.cwd; text = $prompt } | ConvertTo-Json -Depth 8
$session = Invoke-RestMethod -Method Post -Uri "$Hub/api/sessions/pc/create" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $messages = Invoke-RestMethod -Uri "$Hub/api/sessions/pc/codex/$($session.id)/messages" -Headers $headers
    if ($messages.data) {
        $reply = @($messages.data | Where-Object type -eq 'assistant') | Select-Object -Last 1
        if (-not $reply) { continue }
        if (-not $reply.time.completed) { continue }
        @($reply.content | Where-Object type -eq 'text' | ForEach-Object text) -join "`n"
        exit 0
    }
} while ([datetime]::UtcNow -lt $deadline)
throw "Bot is still running in session $($session.id); inspect that session before delegating the same work again"
