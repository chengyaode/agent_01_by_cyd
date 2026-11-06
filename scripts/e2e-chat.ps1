param([string]$Msg, [string]$Session, [int]$Port = 8080)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$bodyFile = Join-Path $env:TEMP 'e2e-body.json'
$respFile = Join-Path $env:TEMP 'e2e-resp.json'

$body = @{ message = $Msg; sessionId = $Session } | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($bodyFile, $body, (New-Object System.Text.UTF8Encoding($false)))

$url = 'http://127.0.0.1:' + $Port + '/api/agents/default/chat'
$sw = [System.Diagnostics.Stopwatch]::StartNew()
curl.exe -s -X POST $url `
  -H 'Content-Type: application/json; charset=utf-8' `
  -H 'X-API-Key: change-me' `
  --data-binary ('@' + $bodyFile) `
  -o $respFile
$sw.Stop()

$raw = [System.IO.File]::ReadAllText($respFile, [System.Text.Encoding]::UTF8)
Write-Output ('WALL_MS: ' + $sw.ElapsedMilliseconds)
try {
  $r = $raw | ConvertFrom-Json
  Write-Output ('SESSION: ' + $r.sessionId)
  Write-Output ('ELAPSED_MS: ' + $r.elapsedMs)
  Write-Output '--- REPLY ---'
  Write-Output $r.reply
} catch {
  Write-Output '--- RAW ---'
  Write-Output $raw
}