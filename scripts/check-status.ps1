Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
  Write-Output ("PID=" + $_.ProcessId + " START=" + $_.CreationDate + " CMD=" + $_.CommandLine)
}
Write-Output '--- LISTEN 8080 ---'
netstat -ano | Select-String 'LISTENING' | Select-String ':8080'
Write-Output '--- TAIL app-run.log ---'
if (Test-Path 'logs\app-run.log') { Get-Content 'logs\app-run.log' -Tail 12 }