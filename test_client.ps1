$tcpClient = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 6379)
$stream = $tcpClient.GetStream()
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$writer = New-Object System.IO.StreamWriter($stream, $utf8NoBom)
$reader = New-Object System.IO.StreamReader($stream, $utf8NoBom)
$writer.AutoFlush = $true

function Send-Cmd($cmd) {
    $writer.Write($cmd + "`r`n")
    Write-Host "> $cmd"
    Start-Sleep -Milliseconds 60
    while ($stream.DataAvailable) {
        $line = $reader.ReadLine()
        Write-Host "< $line"
    }
}

Send-Cmd "PING"
Send-Cmd "SET greeting 'Hello from Redis in Java 25!'"
Send-Cmd "GET greeting"
Send-Cmd "HSET user:profile name 'Alice' role 'Engineer' location 'Cloud'"
Send-Cmd "HGETALL user:profile"
Send-Cmd "LPUSH devops:tasks 'Deploy to Cloud' 'Run Integration Tests'"
Send-Cmd "LRANGE devops:tasks 0 -1"
Send-Cmd "SADD active:tags 'java25' 'redis' 'virtual-threads'"
Send-Cmd "SMEMBERS active:tags"
Send-Cmd "DBSIZE"
Send-Cmd "INFO"

$tcpClient.Close()
