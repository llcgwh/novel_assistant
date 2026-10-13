# Run with Windows PowerShell 5.1. All services and files belong to a temporary
# fixture; no project database, Java runtime, npm install or real app is used.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

if ($env:OS -ne 'Windows_NT') {
    throw 'This lifecycle test must run on Windows.'
}

$SourceRoot = Split-Path -Parent $PSScriptRoot
$Fixture = Join-Path ([IO.Path]::GetTempPath()) ('墨境 Windows 回归 ' + [guid]::NewGuid().ToString('N'))
$Launcher = Join-Path $Fixture 'scripts\dev-windows.ps1'
$StateDirectory = Join-Path $Fixture '.local-dev\windows'
$Markers = Join-Path $Fixture 'fake markers'
$Calls = Join-Path $Fixture 'calls'
$Utf8 = New-Object Text.UTF8Encoding($true)
$Tracked = New-Object Collections.ArrayList
$EnvironmentNames = @('PATH', 'MVN', 'JAVA_HOME', 'INK_BACKEND_PORT', 'INK_FRONTEND_PORT', 'INK_START_TIMEOUT',
    'INK_TEST_MARKERS', 'INK_TEST_BACKEND_MODE', 'INK_TEST_FRONTEND_MODE')
$SavedEnvironment = @{}
foreach ($Name in $EnvironmentNames) {
    $SavedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-FreePort {
    $Listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)
    try {
        $Listener.Start()
        return $Listener.LocalEndpoint.Port
    } finally { $Listener.Stop() }
}

function Test-Port([int]$Port) {
    $Client = New-Object Net.Sockets.TcpClient
    try {
        $Connection = $Client.ConnectAsync('127.0.0.1', $Port)
        return ($Connection.Wait(300) -and $Client.Connected)
    } catch { return $false } finally { $Client.Dispose() }
}

function Test-Http([int]$Port) {
    try {
        $Response = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/novels" -TimeoutSec 2
        return ($Response.StatusCode -eq 200)
    } catch { return $false }
}

function Test-Alive($Record) {
    try {
        $Process = Get-Process -Id ([int]$Record.pid) -ErrorAction Stop
        return ([string]$Process.StartTime.ToUniversalTime().Ticks -eq [string]$Record.born)
    } catch { return $false }
}

function Wait-Until([scriptblock]$Check, [string]$Message, [int]$Seconds = 6) {
    $Deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        if (& $Check) { return }
        Start-Sleep -Milliseconds 100
    } while ([DateTime]::UtcNow -lt $Deadline)
    throw $Message
}

function Read-Record([string]$Service) {
    $Path = Join-Path $StateDirectory ($Service + '.json')
    if (Test-Path -LiteralPath $Path) {
        return (Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json)
    }
    return $null
}

function Remember-Processes {
    foreach ($Service in @('backend', 'frontend')) {
        $Record = Read-Record $Service
        if ($null -ne $Record) { [void]$Tracked.Add($Record) }
    }
    if (Test-Path -LiteralPath $Markers) {
        foreach ($File in @(Get-ChildItem -LiteralPath $Markers -Filter '*.json')) {
            [void]$Tracked.Add((Get-Content -LiteralPath $File.FullName -Raw -Encoding UTF8 | ConvertFrom-Json))
        }
    }
}

function Get-FixtureProcesses {
    return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessId -ne $PID -and $_.CommandLine -and
        $_.CommandLine.IndexOf($Fixture, [StringComparison]::OrdinalIgnoreCase) -ge 0
    })
}

function Invoke-Launcher([string]$Action, [string]$Service = 'all', [switch]$Batch) {
    $Info = New-Object Diagnostics.ProcessStartInfo
    if ($Batch) {
        $Info.FileName = $env:ComSpec
        $Info.Arguments = '/d /s /c ""' + (Join-Path $Fixture '墨境控制台.bat') + '" ' + $Action + ' ' + $Service + '"'
        # Resolve the repo using the entrypoint location, never the caller's cwd.
        $Info.WorkingDirectory = [IO.Path]::GetTempPath()
    } else {
        $Info.FileName = (Join-Path $PSHOME 'powershell.exe')
        $Info.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $Launcher + '" ' + $Action + ' ' + $Service
        $Info.WorkingDirectory = $Fixture
    }
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    $Process = New-Object Diagnostics.Process
    $Process.StartInfo = $Info
    Assert-True ($Process.Start()) 'Could not launch the controller.'
    $OutputTask = $Process.StandardOutput.ReadToEndAsync()
    $ErrorTask = $Process.StandardError.ReadToEndAsync()
    try {
        Assert-True ($Process.WaitForExit(45000)) "Controller hung: $Action $Service"
        Assert-True ($OutputTask.Wait(4000)) "Controller stdout is still held open: $Action $Service"
        Assert-True ($ErrorTask.Wait(4000)) "Controller stderr is still held open: $Action $Service"
        $Result = [pscustomobject]@{
            Code = $Process.ExitCode
            Output = $OutputTask.Result + $ErrorTask.Result
        }
        $CallName = [guid]::NewGuid().ToString('N') + '-' + $Action + '-' + $Service + '.log'
        [IO.File]::WriteAllText((Join-Path $Calls $CallName), $Result.Output, $Utf8)
        Remember-Processes
        return $Result
    } finally {
        if (-not $Process.HasExited) { $Process.Kill() }
        $Process.Dispose()
    }
}

function Assert-Success($Result, [string]$Message) {
    Assert-True ($Result.Code -eq 0) ($Message + "`n" + $Result.Output)
}

function Assert-Stopped {
    Remember-Processes
    Wait-Until { -not (Test-Port $BackendPort) -and -not (Test-Port $FrontendPort) } 'A fixture port remains open.'
    foreach ($Record in $Tracked) {
        Wait-Until { -not (Test-Alive $Record) } ("Fixture process survived stop: " + $Record.pid)
    }
    Wait-Until { @(Get-FixtureProcesses).Count -eq 0 } 'An unrecorded fixture wrapper or child survived stop.'
}

try {
    foreach ($Directory in @('scripts', 'backend', 'frontend-vue\node_modules\vite\bin', 'fake tools', 'fake markers', 'calls')) {
        [void](New-Item -ItemType Directory -Path (Join-Path $Fixture $Directory) -Force)
    }
    Copy-Item -LiteralPath (Join-Path $SourceRoot 'scripts\dev-windows.ps1') -Destination $Launcher
    Copy-Item -LiteralPath (Join-Path $SourceRoot '墨境控制台.bat') -Destination $Fixture
    [IO.File]::WriteAllText((Join-Path $Fixture 'frontend-vue\node_modules\vite\bin\vite.js'), '', $Utf8)

    $FakeTools = Join-Path $Fixture 'fake tools'
    $FakeService = @'
param([string]$Kind)
$ErrorActionPreference = 'Stop'
$Port = if ($Kind -eq 'backend') { [int]$env:SERVER_PORT } else {
    if ($env:INK_TEST_COMMAND_ARGS -notmatch '--port\s+(\d+)') { throw 'Missing frontend --port argument.' }
    [int]$Matches[1]
}
$Self = Get-Process -Id $PID
$Marker = @{ pid = $PID; born = [string]$Self.StartTime.ToUniversalTime().Ticks; kind = $Kind }
[IO.File]::WriteAllText((Join-Path $env:INK_TEST_MARKERS ($Kind + '-' + $PID + '.json')),
    ($Marker | ConvertTo-Json), (New-Object Text.UTF8Encoding($true)))
Write-Output ('FAKE ' + $Kind + ' started on ' + $Port)
$Mode = [Environment]::GetEnvironmentVariable('INK_TEST_' + $Kind.ToUpperInvariant() + '_MODE')
if ($Mode -eq 'fail') { Write-Output 'Intentional fixture failure'; exit 19 }
if ($Mode -eq 'timeout') { while ($true) { Start-Sleep -Seconds 1 } }
$Listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
$Listener.Start()
try {
    while ($true) {
        $Client = $Listener.AcceptTcpClient()
        try {
            $Stream = $Client.GetStream()
            $Stream.ReadTimeout = 1500
            $Reader = New-Object IO.StreamReader($Stream)
            $Line = $Reader.ReadLine()
            if ($null -eq $Line) { continue }
            while ($Line -ne '') {
                $Line = $Reader.ReadLine()
                if ($null -eq $Line) { break }
            }
            $Bytes = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 200 OK`r`nContent-Type: application/json`r`nContent-Length: 2`r`nConnection: close`r`n`r`n[]")
            $Stream.Write($Bytes, 0, $Bytes.Length)
            $Stream.Flush()
        } catch [IO.IOException] { } finally { $Client.Close() }
    }
} finally { $Listener.Stop() }
'@
    [IO.File]::WriteAllText((Join-Path $FakeTools 'fake-service.ps1'), $FakeService, $Utf8)
    foreach ($Command in @(@{File='mvn.cmd'; Kind='backend'}, @{File='npm.cmd'; Kind='frontend'})) {
        $CmdText = '@echo off' + "`r`n" + 'set "INK_TEST_COMMAND_ARGS=%*"' + "`r`n" + '"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0fake-service.ps1" -Kind ' + $Command.Kind + "`r`n" + 'exit /b %errorlevel%' + "`r`n"
        [IO.File]::WriteAllText((Join-Path $FakeTools $Command.File), $CmdText, [Text.Encoding]::ASCII)
    }

    $BackendPort = Get-FreePort
    do { $FrontendPort = Get-FreePort } while ($FrontendPort -eq $BackendPort)
    do { $UnusedFrontendPort = Get-FreePort } while ($UnusedFrontendPort -in @($BackendPort, $FrontendPort))
    # The env frontend port must win over the file value; MVN must accept spaces.
    $Config = "INK_BACKEND_PORT=$BackendPort`r`nINK_FRONTEND_PORT=$UnusedFrontendPort`r`nINK_START_TIMEOUT=12`r`nMVN=`"$(Join-Path $FakeTools 'mvn.cmd')`"`r`n"
    [IO.File]::WriteAllText((Join-Path $Fixture '.dev.env'), $Config, $Utf8)
    $env:PATH = $FakeTools + ';' + $SavedEnvironment['PATH']
    Remove-Item Env:MVN -ErrorAction SilentlyContinue
    Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue
    Remove-Item Env:INK_BACKEND_PORT -ErrorAction SilentlyContinue
    $env:INK_FRONTEND_PORT = [string]$FrontendPort
    $env:INK_START_TIMEOUT = '15'
    $env:INK_TEST_MARKERS = $Markers
    $env:INK_TEST_BACKEND_MODE = 'serve'
    $env:INK_TEST_FRONTEND_MODE = 'serve'

    Write-Host 'CASE: start, detached lifetime, duplicate start, status, logs, complete process tree stop'
    Assert-Success (Invoke-Launcher start all) 'Starting both fake services failed.'
    $Backend = Read-Record backend
    $Frontend = Read-Record frontend
    Assert-True ($null -ne $Backend -and $null -ne $Frontend) 'Both service records are required.'
    Assert-True ($Backend.port -eq $BackendPort -and $Frontend.port -eq $FrontendPort) 'File configuration or environment priority was ignored.'
    Assert-True ((Test-Alive $Backend) -and (Test-Alive $Frontend)) 'Workers exited with the controller.'
    Assert-True ((Test-Http $BackendPort) -and (Test-Http $FrontendPort)) 'Detached services are not responding.'
    Assert-Success (Invoke-Launcher start all) 'Repeated start failed.'
    Assert-True ((Read-Record backend).pid -eq $Backend.pid -and (Read-Record frontend).pid -eq $Frontend.pid) 'Repeated start created different workers.'
    Assert-Success (Invoke-Launcher status all -Batch) 'The batch entrypoint failed from an unrelated working directory.'
    $env:JAVA_HOME = Join-Path $Fixture 'missing JDK'
    Assert-Success (Invoke-Launcher status all) 'Status failed.'
    $Logs = Invoke-Launcher logs all
    Assert-Success $Logs 'Reading logs failed.'
    Assert-True ($Logs.Output -match 'FAKE backend' -and $Logs.Output -match 'FAKE frontend') 'Logs do not include both service outputs.'
    Assert-Success (Invoke-Launcher stop all) 'Stopping both services failed with an invalid JAVA_HOME.'
    Assert-Stopped
    Assert-Success (Invoke-Launcher stop all) 'Repeated stop should be harmless.'
    Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue

    Write-Host 'CASE: occupied port is neither adopted nor killed'
    $ForeignListener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $BackendPort)
    try {
        $ForeignListener.Start()
        $Blocked = Invoke-Launcher start backend
        Assert-True ($Blocked.Code -ne 0) 'Start accepted an occupied foreign port.'
        Assert-True ($null -eq (Read-Record backend)) 'A foreign listener was adopted.'
        [void](Invoke-Launcher stop backend)
        Assert-True (Test-Port $BackendPort) 'Stop closed a foreign listener.'
    } finally { $ForeignListener.Stop() }

    Write-Host 'CASE: frontend failure rolls back only services started by this invocation'
    $env:INK_TEST_FRONTEND_MODE = 'fail'
    $Failed = Invoke-Launcher start all
    Assert-True ($Failed.Code -ne 0) 'Intentional frontend failure was reported as success.'
    Assert-Stopped
    Assert-Success (Invoke-Launcher start backend) 'Could not start the preexisting backend.'
    $Previous = Read-Record backend
    $Failed = Invoke-Launcher start all
    Assert-True ($Failed.Code -ne 0) 'Frontend failure should still fail with an existing backend.'
    Assert-True ((Read-Record backend).pid -eq $Previous.pid -and (Test-Http $BackendPort)) 'Rollback stopped the backend from an earlier invocation.'
    Assert-Success (Invoke-Launcher stop all) 'Cleanup after failed frontend did not complete.'
    Assert-Stopped
    $env:INK_TEST_FRONTEND_MODE = 'serve'

    Write-Host 'CASE: readiness timeout cleans wrapper and child process'
    $env:INK_TEST_BACKEND_MODE = 'timeout'
    $env:INK_START_TIMEOUT = '3'
    $Failed = Invoke-Launcher start backend
    Assert-True ($Failed.Code -ne 0) 'A non-listening service passed its readiness check.'
    Assert-Stopped
    $env:INK_TEST_BACKEND_MODE = 'serve'
    $env:INK_START_TIMEOUT = '15'

    Write-Host 'CASE: wrong identity token cannot stop a live worker'
    Assert-Success (Invoke-Launcher start backend) 'Could not start identity fixture.'
    $Original = Read-Record backend
    $OriginalJson = $Original | ConvertTo-Json
    $Original.token = [guid]::NewGuid().ToString('N')
    $RecordPath = Join-Path $StateDirectory 'backend.json'
    [IO.File]::WriteAllText($RecordPath, ($Original | ConvertTo-Json), $Utf8)
    [void](Invoke-Launcher stop backend)
    Assert-True ((Test-Alive $Original) -and (Test-Http $BackendPort)) 'An identity-mismatched process was stopped.'
    [IO.File]::WriteAllText($RecordPath, $OriginalJson, $Utf8)
    Assert-Success (Invoke-Launcher stop backend) 'The restored identity could not stop its worker.'
    Assert-Stopped

    Write-Host 'CASE: dead PID record is replaced safely'
    $Stale = @{pid=2147483647; born='0'; token=[guid]::NewGuid().ToString('N'); name='backend'; port=$BackendPort}
    [IO.File]::WriteAllText($RecordPath, ($Stale | ConvertTo-Json), $Utf8)
    Assert-Success (Invoke-Launcher start backend) 'A dead PID record blocked startup.'
    Assert-True ((Read-Record backend).pid -ne $Stale.pid -and (Test-Http $BackendPort)) 'The stale record was not replaced by a ready worker.'
    Assert-Success (Invoke-Launcher stop backend) 'Could not stop the replacement worker.'
    Assert-Stopped
    Write-Host 'PASS: Windows launcher lifecycle checks completed.'
} catch {
    Write-Host ('FAIL: ' + $_.Exception.Message)
    foreach ($Directory in @($StateDirectory, $Calls)) {
        if (Test-Path -LiteralPath $Directory) {
            foreach ($Log in @(Get-ChildItem -LiteralPath $Directory -Filter '*.log')) {
                Write-Host ('--- ' + $Log.Name)
                Get-Content -LiteralPath $Log.FullName -Tail 60 -Encoding UTF8 | Write-Host
            }
        }
    }
    throw
} finally {
    # Remove only processes whose full command line names this unique fixture.
    # This also catches a worker that failed before it could write a PID record.
    $Owned = @(Get-FixtureProcesses)
    foreach ($Process in $Owned) {
        Stop-Process -Id $Process.ProcessId -Force -ErrorAction SilentlyContinue
    }
    foreach ($Name in $EnvironmentNames) {
        [Environment]::SetEnvironmentVariable($Name, $SavedEnvironment[$Name], 'Process')
    }
    Start-Sleep -Milliseconds 300
    if (Test-Path -LiteralPath $Fixture) { Remove-Item -LiteralPath $Fixture -Recurse -Force }
}
