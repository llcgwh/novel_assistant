#requires -Version 5.1
<# Windows local development console. No additional PowerShell modules required. #>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('menu', 'start', 'stop', 'status', 'logs', '_worker')]
    [string]$Action = 'menu',
    [Parameter(Position = 1)]
    [ValidateSet('all', 'backend', 'frontend')]
    [string]$Service = 'all',
    [switch]$Follow,
    [string]$Token,
    [int]$WorkerPort
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$script:Root = Split-Path -Parent $PSScriptRoot
$script:ScriptFile = $PSCommandPath
$script:StateDirectory = Join-Path $script:Root '.local-dev\windows'
$script:PowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$script:Names = @('backend', 'frontend')
$script:Utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-EnvironmentFile([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $values }
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        $lineNumber++
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        if ($text.StartsWith('export ')) { $text = $text.Substring(7).TrimStart() }
        if ($text -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            throw "$(Split-Path -Leaf $Path) 第 $lineNumber 行不是 KEY=value"
        }
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.StartsWith('"') -or $value.StartsWith("'")) {
            $quote = $value.Substring(0, 1)
            $closing = $value.IndexOf($quote, 1)
            if ($closing -lt 1) { throw "$(Split-Path -Leaf $Path) 第 $lineNumber 行缺少闭合引号" }
            $tail = $value.Substring($closing + 1).Trim()
            if ($tail -and -not $tail.StartsWith('#')) {
                throw "$(Split-Path -Leaf $Path) 第 $lineNumber 行引号后只能有注释"
            }
            $value = $value.Substring(1, $closing - 1)
        } else {
            $value = [regex]::Replace($value, '\s+#.*$', '').Trim()
            if ($value -match '\s') {
                throw "$(Split-Path -Leaf $Path) 第 $lineNumber 行的值含空格，请加引号"
            }
        }
        # Literal values: no interpolation, command expansion, or backslash escapes.
        $values[$key] = $value
    }
    return $values
}

function Initialize-Configuration {
    $values = Read-EnvironmentFile (Join-Path $script:Root '.dev.env')
    $inherited = [Environment]::GetEnvironmentVariables('Process')
    foreach ($key in $values.Keys) {
        if (-not $inherited.Contains($key)) {
            [Environment]::SetEnvironmentVariable($key, [string]$values[$key], 'Process')
        }
    }
    $script:Ports = @{}
    foreach ($name in $script:Names) {
        $default = if ($name -eq 'backend') { 8080 } else { 3000 }
        $configured = [Environment]::GetEnvironmentVariable("INK_$($name.ToUpperInvariant())_PORT", 'Process')
        $port = 0
        if ([string]::IsNullOrEmpty($configured)) { $port = $default }
        elseif (-not [int]::TryParse($configured, [ref]$port)) { throw "$name 端口不是整数" }
        if ($port -lt 1024 -or $port -gt 65535) { throw '端口范围应为 1024–65535' }
        $script:Ports[$name] = $port
    }
    if ($script:Ports.backend -eq $script:Ports.frontend) { throw '前后端端口不能相同' }
    $script:StartTimeout = 120.0
    if ($env:INK_START_TIMEOUT) {
        if (-not [double]::TryParse($env:INK_START_TIMEOUT, [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture, [ref]$script:StartTimeout) -or
                [double]::IsNaN($script:StartTimeout) -or [double]::IsInfinity($script:StartTimeout) -or
                $script:StartTimeout -le 0 -or $script:StartTimeout -gt 3600) {
            throw 'INK_START_TIMEOUT 应为 0 到 3600 之间的秒数（不含 0）'
        }
    }
    [void][IO.Directory]::CreateDirectory($script:StateDirectory)
}

function Get-RecordPath([string]$Name) { return Join-Path $script:StateDirectory "$Name.json" }
function Get-LogPath([string]$Name) { return Join-Path $script:StateDirectory "$Name.log" }
function Add-Log([string]$Name, [string]$Message) {
    [IO.File]::AppendAllText((Get-LogPath $Name), $Message + [Environment]::NewLine, $script:Utf8)
}
function Read-Record([string]$Name) {
    try {
        $path = Get-RecordPath $Name
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $null }
        $record = [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) | ConvertFrom-Json
        if ($record.name -ne $Name) { return $null }
        return $record
    } catch { return $null }
}
function Get-OwnedProcess($Record) {
    $process = $null
    try {
        if ($null -eq $Record -or $Record.name -notin $script:Names -or
            [int]$Record.pid -le 1 -or [string]$Record.token -notmatch '^[a-f0-9]{32}$') { return $null }
        $process = Get-Process -Id ([int]$Record.pid) -ErrorAction Stop
        # Acquire a kernel handle first: a reused PID cannot redirect a later Kill().
        $null = $process.Handle
        if ($process.HasExited -or $process.StartTime.ToUniversalTime().Ticks.ToString() -ne [string]$Record.born) {
            $process.Dispose()
            return $null
        }
        $info = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$Record.pid)" -ErrorAction Stop
        $commandLine = [string]$info.CommandLine
        if (-not $commandLine -or
            $commandLine.IndexOf($script:ScriptFile, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
            $commandLine -notmatch ('-Action\s+_worker\s+-Service\s+' + [regex]::Escape([string]$Record.name) + '\s+-Token\s+' + [regex]::Escape([string]$Record.token) + '(?:\s|$)')) {
            $process.Dispose()
            return $null
        }
        if ($process.HasExited) { $process.Dispose(); return $null }
        return $process
    } catch {
        if ($null -ne $process) { $process.Dispose() }
        return $null
    }
}
function Test-Owned($Record) {
    $process = Get-OwnedProcess $Record
    if ($null -eq $process) { return $false }
    $process.Dispose()
    return $true
}
function Test-Port([int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(250)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false }
    finally { $client.Close() }
}
function Test-Http([string]$Name, [int]$Port) {
    $path = if ($Name -eq 'backend') { '/api/novels' } else { '/' }
    $response = $null
    try {
        $request = [Net.HttpWebRequest]::Create("http://127.0.0.1:$Port$path")
        $request.Proxy = $null
        $request.Timeout = 600
        $request.ReadWriteTimeout = 600
        $response = $request.GetResponse()
        return [int]$response.StatusCode -eq 200
    } catch { return $false }
    finally { if ($null -ne $response) { $response.Close() } }
}
function Get-ServiceStatus([string]$Name) {
    $record = Read-Record $Name
    $running = Test-Owned $record
    $port = if ($running) { [int]$record.port } else { $script:Ports[$Name] }
    $ready = $running -and (Test-Http $Name $port)
    $label = if ($ready) { '运行中' } elseif ($running) { '启动中' }
             elseif (Test-Port $port) { '端口被其他程序占用' } else { '已停止' }
    return [pscustomobject]@{ name = $Name; running = $running; ready = $ready; port = $port; label = $label }
}
function Show-Status([string]$Name = 'all') {
    $names = if ($Name -eq 'all') { $script:Names } else { @($Name) }
    foreach ($item in $names) {
        $status = Get-ServiceStatus $item
        Write-Host ("{0}: {1} · {2} · {3}" -f $item, $status.label, $status.port, (Get-LogPath $item))
    }
}
function Find-Executable([string[]]$Candidates, [string]$ErrorMessage) {
    foreach ($candidate in $Candidates) {
        if (-not $candidate) { continue }
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path -LiteralPath $candidate).Path }
        $found = Get-Command -Name $candidate -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $found) { return $found.Source }
    }
    throw $ErrorMessage
}
function Get-ServiceCommand([string]$Name) {
    if ($Name -eq 'frontend') {
        $backend = Read-Record 'backend'
        if ((Test-Owned $backend) -and [int]$backend.port -ne $script:Ports.backend) {
            throw 'backend 仍运行在旧端口，请先关闭后端，再按新配置启动'
        }
        $executable = Find-Executable @('npm.cmd', 'npm') '找不到 npm.cmd，请安装 Node.js 22.12+ 并重新打开控制台'
        $directory = Join-Path $script:Root 'frontend-vue'
        if (-not (Test-Path -LiteralPath (Join-Path $directory 'node_modules\vite\bin\vite.js') -PathType Leaf)) {
            throw '前端依赖未安装：请先在 frontend-vue 目录执行 npm ci'
        }
        $target = 'http://127.0.0.1:' + $script:Ports.backend
        foreach ($file in @('.env', '.env.local', '.env.development', '.env.development.local')) {
            $values = Read-EnvironmentFile (Join-Path $directory $file)
            if ($values.ContainsKey('VITE_API_PROXY_TARGET') -and $values['VITE_API_PROXY_TARGET'] -and
                $values['VITE_API_PROXY_TARGET'].TrimEnd('/') -notin @($target, $target.Replace('127.0.0.1', 'localhost'))) {
                throw "frontend-vue/$file 的代理目标与控制台后端端口不同，请先调整"
            }
            if ($values.ContainsKey('VITE_API_BASE_URL') -and $values['VITE_API_BASE_URL'] -and
                $values['VITE_API_BASE_URL'].TrimEnd('/') -ne '/api') {
                throw "frontend-vue/$file 的 VITE_API_BASE_URL 与本地 /api 代理不同，请先调整"
            }
        }
        $env:VITE_API_PROXY_TARGET = $target
        $env:VITE_API_BASE_URL = '/api'
        return [pscustomobject]@{ executable = $executable; directory = $directory;
            arguments = @('run', 'dev', '--', '--host', '127.0.0.1', '--port', [string]$script:Ports.frontend, '--strictPort') }
    }
    if ($env:JAVA_HOME) {
        if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe') -PathType Leaf)) {
            throw 'JAVA_HOME 中找不到 bin\java.exe，请设置为 Windows JDK 安装目录'
        }
        $env:Path = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:Path
    }
    $candidates = if ($env:MVN) { @($env:MVN) } else { @('mvn.cmd', 'mvn') }
    $executable = Find-Executable $candidates '找不到 Maven；请安装并加入 PATH，或在 .dev.env 设置 MVN="C:\完整路径\mvn.cmd"'
    $directory = Join-Path $script:Root 'backend'
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { throw '找不到 backend 目录，请保留仓库目录结构' }
    $env:SERVER_PORT = [string]$script:Ports.backend
    $env:SERVER_ADDRESS = '127.0.0.1'
    return [pscustomobject]@{ executable = $executable; directory = $directory;
        arguments = @('-B', '-Dstyle.color=never', 'spring-boot:run') }
}
function Invoke-ControlLock([scriptblock]$Operation) {
    $stream = $null
    try {
        try {
            $stream = [IO.File]::Open((Join-Path $script:StateDirectory 'control.lock'), [IO.FileMode]::OpenOrCreate,
                [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        } catch { throw '另一个控制台正在启停服务，请稍后再试' }
        & $Operation
    } finally { if ($null -ne $stream) { $stream.Dispose() } }
}
function Stop-ServiceCore([string]$Name) {
    $record = Read-Record $Name
    $process = Get-OwnedProcess $record
    if ($null -eq $process) { Write-Host "$Name 未由此控制台运行，无需关闭"; return }
    try {
        if (-not $process.HasExited) { $process.Kill() }
        if (-not $process.WaitForExit(10000)) { throw "$Name 停止超时，请查看日志" }
        # The worker owns a kill-on-close Job Object; its exit terminates descendants.
        Add-Log $Name ("[控制台] {0} 已停止本次服务进程树。" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
        $path = Get-RecordPath $Name
        $current = Read-Record $Name
        if ($null -ne $current -and $current.token -eq $record.token) { Remove-Item -LiteralPath $path -Force }
        Write-Host "$Name 已关闭"
    } finally { $process.Dispose() }
}
function Start-ServiceCore([string]$Name) {
    $previous = Read-Record $Name
    if (Test-Owned $previous) {
        if ([int]$previous.port -ne $script:Ports[$Name]) { throw "$Name 仍运行在旧端口，请先关闭该服务，再按新配置启动" }
        Write-Host "$Name 已在运行"
        return
    }
    if (Test-Port $script:Ports[$Name]) { throw "$Name 端口 $($script:Ports[$Name]) 已被占用，未启动或停止任何外部进程" }
    $null = Get-ServiceCommand $Name
    $workerToken = [Guid]::NewGuid().ToString('N')
    $process = $null
    $phase = '创建后台进程'
    try {
        Add-Log $Name ("`n--- {0} 启动 {1} ---" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Name)
        $arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $script:ScriptFile +
            '" -Action _worker -Service ' + $Name + ' -Token ' + $workerToken + ' -WorkerPort ' + $script:Ports[$Name]
        $process = Start-Process -FilePath $script:PowerShell -ArgumentList $arguments -WorkingDirectory $script:Root -WindowStyle Hidden -PassThru
        $null = $process.Handle
        $record = [pscustomobject]@{ pid = $process.Id; born = $process.StartTime.ToUniversalTime().Ticks.ToString();
            token = $workerToken; name = $Name; port = $script:Ports[$Name] }
        $phase = '确认后台进程身份'
        $identityDeadline = [DateTime]::UtcNow.AddSeconds([Math]::Min(10, $script:StartTimeout))
        while (-not (Test-Owned $record)) {
            if ($process.HasExited) { throw "后台进程已退出（退出码 $($process.ExitCode)）" }
            if ([DateTime]::UtcNow -ge $identityDeadline) { throw '无法确认本次后台进程身份' }
            Start-Sleep -Milliseconds 100
        }
        $path = Get-RecordPath $Name
        $temporary = $path + '.' + $workerToken + '.tmp'
        [IO.File]::WriteAllText($temporary, ($record | ConvertTo-Json -Compress), $script:Utf8)
        Move-Item -LiteralPath $temporary -Destination $path -Force
        $phase = '等待服务 HTTP 就绪'
        $deadline = [DateTime]::UtcNow.AddSeconds($script:StartTimeout)
        while ([DateTime]::UtcNow -lt $deadline) {
            if ($process.HasExited) { throw "服务已退出（退出码 $($process.ExitCode)）" }
            if (Test-Http $Name $script:Ports[$Name]) {
                if ($process.HasExited -or -not (Test-Owned $record)) { throw '服务就绪时后台进程已退出' }
                Write-Host "$Name 已启动，端口 $($script:Ports[$Name])"
                return
            }
            Start-Sleep -Milliseconds 250
        }
        throw '启动超时（请确认数据库已运行）'
    } catch {
        $reason = $_.Exception.Message
        try { Add-Log $Name "[控制台] ${phase}失败：$reason" } catch { }
        # This retained Process handle refers only to the child started above.
        if ($null -ne $process) {
            try {
                if (-not $process.HasExited) { $process.Kill(); [void]$process.WaitForExit(10000) }
            } catch { }
        }
        $failed = Read-Record $Name
        if ($null -ne $failed -and $failed.token -eq $workerToken) {
            Remove-Item -LiteralPath (Get-RecordPath $Name) -Force -ErrorAction SilentlyContinue
        }
        throw "$Name 启动失败：$reason；请查看 $(Get-LogPath $Name)"
    } finally { if ($null -ne $process) { $process.Dispose() } }
}
function Start-Services([string]$Name) {
    Invoke-ControlLock {
        $names = if ($Name -eq 'all') { $script:Names } else { @($Name) }
        $started = New-Object 'System.Collections.Generic.List[string]'
        try {
            foreach ($item in $names) {
                $existing = Test-Owned (Read-Record $item)
                Start-ServiceCore $item
                if (-not $existing) { $started.Add($item) }
            }
        } catch {
            $failure = $_
            for ($index = $started.Count - 1; $index -ge 0; $index--) {
                try { Stop-ServiceCore $started[$index] } catch { Write-Warning $_.Exception.Message }
            }
            throw $failure
        }
    }
}
function Stop-Services([string]$Name) {
    Invoke-ControlLock {
        $names = if ($Name -eq 'all') { @('frontend', 'backend') } else { @($Name) }
        foreach ($item in $names) { Stop-ServiceCore $item }
    }
}
function Show-Logs([string]$Name, [bool]$WaitForMore) {
    $names = if ($Name -eq 'all') { $script:Names } else { @($Name) }
    $positions = @{}
    foreach ($item in $names) {
        $path = Get-LogPath $item
        Write-Host "--- ${item}: $path ---"
        if (-not (Test-Path -LiteralPath $path)) { [IO.File]::WriteAllText($path, '', $script:Utf8) }
        Get-Content -LiteralPath $path -Encoding UTF8 -Tail 80 | ForEach-Object { Write-Host $_ }
        $positions[$item] = (Get-Item -LiteralPath $path).Length
    }
    if (-not $WaitForMore) { return }
    Write-Host '持续查看日志；Ctrl+C 退出查看，不关闭服务。'
    while ($true) {
        foreach ($item in $names) {
            $path = Get-LogPath $item
            $stream = $null
            $reader = $null
            try {
                $stream = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
                if ($stream.Length -lt $positions[$item]) { $positions[$item] = 0 }
                if ($stream.Length -gt $positions[$item]) {
                    [void]$stream.Seek($positions[$item], [IO.SeekOrigin]::Begin)
                    $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
                    $text = $reader.ReadToEnd()
                    $positions[$item] = $stream.Position
                    if ($text) { Write-Host "[$item]"; Write-Host $text -NoNewline }
                }
            } finally {
                if ($null -ne $reader) { $reader.Dispose() }
                elseif ($null -ne $stream) { $stream.Dispose() }
            }
        }
        Start-Sleep -Milliseconds 350
    }
}
function Initialize-WorkerJob {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class InkWorkerJob {
    private static IntPtr job;
    [StructLayout(LayoutKind.Sequential)] struct BasicLimit {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] struct IoCounters {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimit {
        public BasicLimit BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern IntPtr CreateJobObject(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetInformationJobObject(IntPtr handle, int infoClass, ref ExtendedLimit info, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool AssignProcessToJobObject(IntPtr handle, IntPtr process);
    [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    public static void Attach() {
        job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        ExtendedLimit info = new ExtendedLimit();
        info.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if (!SetInformationJobObject(job, 9, ref info, (uint)Marshal.SizeOf(typeof(ExtendedLimit)))) {
            int error = Marshal.GetLastWin32Error(); CloseHandle(job); job = IntPtr.Zero;
            throw new Win32Exception(error);
        }
        if (!AssignProcessToJobObject(job, GetCurrentProcess())) {
            int error = Marshal.GetLastWin32Error(); CloseHandle(job); job = IntPtr.Zero;
            throw new Win32Exception(error);
        }
        // Keep this non-inheritable handle open for the worker lifetime only.
        // OS process teardown closes it and terminates every descendant in the job.
    }
}
'@
    [InkWorkerJob]::Attach()
}
function Invoke-Worker {
    if ($Service -notin $script:Names -or $Token -notmatch '^[a-f0-9]{32}$' -or $WorkerPort -ne $script:Ports[$Service]) {
        throw '无效的内部后台进程参数'
    }
    $exitCode = 1
    try {
        Initialize-WorkerJob
        $command = Get-ServiceCommand $Service
        Set-Location -LiteralPath $command.directory
        Add-Log $Service '[控制台] 后台进程已就绪，正在运行服务命令。'
        $arguments = $command.arguments
        # Windows PowerShell supports .cmd invocation and routes native stderr into
        # the pipeline as ErrorRecord; it must not terminate a healthy Maven run.
        $ErrorActionPreference = 'Continue'
        & $command.executable @arguments 2>&1 | ForEach-Object {
            Add-Log $Service ([string]$_)
        }
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        Add-Log $Service "[控制台] 服务退出，退出码 $exitCode"
    } catch {
        try { Add-Log $Service ("[控制台] 后台进程失败：" + $_.Exception.Message) } catch { }
        $exitCode = 1
    }
    exit $exitCode
}
function Open-LogWindow([string]$Name) {
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $script:ScriptFile + '" logs ' + $Name + ' -Follow'
    Start-Process -FilePath $script:PowerShell -ArgumentList $arguments -WorkingDirectory $script:Root
}
function Show-Menu {
    while ($true) {
        Write-Host "`n墨境 · Windows 开发控制台" -ForegroundColor Cyan
        Show-Status
        Write-Host 'S 启动全部   X 关闭全部   B 后端启停   F 前端启停'
        Write-Host '1 后端日志   2 前端日志   L 日志目录   O 打开网页'
        Write-Host 'R 刷新状态   Q 退出控制台（服务继续运行）'
        $choice = (Read-Host '选择').Trim().ToUpperInvariant()
        try {
            switch ($choice) {
                'S' { Start-Services 'all' }
                'X' { Stop-Services 'all' }
                'B' { if (Test-Owned (Read-Record 'backend')) { Stop-Services 'backend' } else { Start-Services 'backend' } }
                'F' { if (Test-Owned (Read-Record 'frontend')) { Stop-Services 'frontend' } else { Start-Services 'frontend' } }
                '1' { Open-LogWindow 'backend' }
                '2' { Open-LogWindow 'frontend' }
                'L' { Start-Process -FilePath 'explorer.exe' -ArgumentList ('"' + $script:StateDirectory + '"') }
                'O' { Start-Process ('http://localhost:' + $script:Ports.frontend) }
                'R' { }
                'Q' { Write-Host '已退出控制台，服务保持现状。'; return }
                default { Write-Host '请选择菜单中的按键。' }
            }
        } catch { Write-Host $_.Exception.Message -ForegroundColor Red }
    }
}

try {
    Initialize-Configuration
    switch ($Action) {
        '_worker' { Invoke-Worker }
        'start' { Start-Services $Service }
        'stop' { Stop-Services $Service }
        'status' { Show-Status $Service }
        'logs' { Show-Logs $Service ([bool]$Follow) }
        'menu' { Show-Menu }
    }
    exit 0
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
