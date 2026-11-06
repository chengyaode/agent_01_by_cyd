$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$data = Get-Content "d:\demo\agent_01\probe-msgs.json" -Raw -Encoding UTF8 | ConvertFrom-Json

function Probe([string]$msg, [string]$sid) {
    $body = @{ message = $msg; sessionId = $sid; userId = "probe" } | ConvertTo-Json
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:8080/api/agents/default/chat/stream" `
            -Method Post -ContentType "application/json; charset=utf-8" `
            -Headers @{ "X-API-Key" = "change-me" } -Body ([Text.Encoding]::UTF8.GetBytes($body)) `
            -TimeoutSec 240 -UseBasicParsing
        $tools = [regex]::Matches($resp.Content, '"tool"\s*:\s*"([^"]+)"') | ForEach-Object { $_.Groups[1].Value }
        if (-not $tools) { $tools = @("(none)") }
        return ($tools -join " -> ")
    } catch {
        return "ERROR " + $_.Exception.Message
    }
}

$i = 0
foreach ($m in $data.single) {
    $i++
    Write-Output ("[A$i] $m  =>  " + (Probe $m "hr-a-$i"))
}
Write-Output "--- same session, multi turn ---"
$i = 0
foreach ($m in $data.multi) {
    $i++
    Write-Output ("[B$i] $m  =>  " + (Probe $m "hr-b-multi"))
}
