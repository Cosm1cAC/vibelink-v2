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
if ($bot.engine -ne 'deepseek') { throw 'Dispatch target must be a DeepSeek Bot' }
$prompt = Get-Content -LiteralPath $PromptFile -Raw -Encoding utf8
$body = @{ agent = 'deepseek'; botId = $BotId; cwd = $bot.cwd; text = $prompt } | ConvertTo-Json -Depth 8
$session = Invoke-RestMethod -Method Post -Uri "$Hub/api/sessions/pc/create" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
$deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
do {
    Start-Sleep -Seconds 2
    $messages = Invoke-RestMethod -Uri "$Hub/api/sessions/pc/deepseek/$($session.id)/messages" -Headers $headers
    if (-not $messages.running) {
        $reply = @($messages.messages | Where-Object role -eq 'assistant') | Select-Object -Last 1
        if (-not $reply) { throw 'Bot completed without a reply' }
        $reply.text
        exit 0
    }
} while ([datetime]::UtcNow -lt $deadline)
throw "Bot is still running in session $($session.id); inspect that session before delegating the same work again"
