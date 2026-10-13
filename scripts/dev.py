#!/usr/bin/env python3
"""Local frontend/backend launcher. Python 3.9+, macOS/Linux, no extra packages."""
from __future__ import annotations

import argparse
import contextlib
import fcntl
import json
import os
from pathlib import Path
import select
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(__file__).resolve()
NAMES = ('backend', 'frontend')


def read_env(path):
    result = {}
    if not path.exists():
        return result
    for number, line in enumerate(path.read_text().splitlines(), 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:]
        key, sep, value = line.partition('=')
        key = key.strip()
        if not sep or not key.replace('_', 'a').isalnum() or key[0].isdigit():
            raise RuntimeError(f'{path.name} 第 {number} 行不是 KEY=value')
        parts = shlex.split(value, comments=True)
        if len(parts) > 1:
            raise RuntimeError(f'{path.name} 第 {number} 行的值含空格，请加引号')
        result[key] = parts[0] if parts else ''
    return result


def process_info(pid):
    try:
        result = subprocess.run(
            ['ps', '-ww', '-p', str(int(pid)), '-o', 'pgid=', '-o', 'lstart=', '-o', 'args='],
            capture_output=True, text=True, timeout=2,
        )
        parts = result.stdout.strip().split(None, 6)
        if len(parts) == 7:
            return {'group': int(parts[0]), 'born': ' '.join(parts[1:6]), 'command': parts[6]}
    except (OSError, ValueError, subprocess.TimeoutExpired):
        pass
    return None


def wait_worker_ready(process, descriptor, token, timeout):
    """Wait until Python has exec'd and installed the worker signal handlers."""
    deadline = time.monotonic() + timeout
    received = b''
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f'启动进程在就绪前退出（退出码 {process.returncode}）')
        if not select.select([descriptor], [], [], min(.1, max(0, deadline - time.monotonic())))[0]:
            continue
        chunk = os.read(descriptor, 4096)
        if not chunk:
            # Reap only our own child; its stderr is already in the service log.
            try:
                code = process.wait(timeout=.2)
            except subprocess.TimeoutExpired:
                code = '尚未退出'
            raise RuntimeError(f'启动进程就绪通道关闭（退出码 {code}）')
        received += chunk
        if b'\n' in received:
            if received != (token + '\n').encode():
                raise RuntimeError('启动进程就绪标识不匹配')
            return
    raise RuntimeError('等待启动进程就绪超时')


def port_busy(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=.2):
            return True
    except OSError:
        return False


def http_ready(url):
    try:
        # Local checks must not go through a shell/system HTTP proxy.
        with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url, timeout=.4) as response:
            return response.status == 200
    except (OSError, urllib.error.URLError):
        return False


class Manager:
    def __init__(self, root=ROOT):
        self.root = Path(root)
        self.env = {**read_env(self.root / '.dev.env'), **os.environ}
        self.directory = self.root / '.local-dev'
        self.directory.mkdir(mode=0o700, exist_ok=True)
        self.ports = {name: int(self.env.get(f'INK_{name.upper()}_PORT', default))
                      for name, default in [('backend', 8080), ('frontend', 3000)]}
        if any(not 1024 <= p <= 65535 for p in self.ports.values()) or len(set(self.ports.values())) != 2:
            raise RuntimeError('前后端端口应不同，范围为 1024–65535')
        self.frontend_url = f'http://localhost:{self.ports["frontend"]}'
        self.timeout = float(self.env.get('INK_START_TIMEOUT', '120'))

    def log_path(self, name):
        return self.directory / f'{name}.log'

    def record(self, name):
        try:
            return json.loads((self.directory / f'{name}.json').read_text())
        except (OSError, ValueError):
            return None

    def owned(self, record):
        if (not isinstance(record, dict) or record.get('name') not in NAMES
                or not isinstance(record.get('pid'), int) or record['pid'] <= 1
                or not isinstance(record.get('token'), str) or not record['token']):
            return False
        info = process_info(record.get('pid', 0))
        return bool(info and info['group'] == record['pid']
                    and info['born'] == record.get('born')
                    and f'_worker {record["name"]} {record["token"]}' in info['command'])

    def status(self, name):
        record = self.record(name)
        running = self.owned(record)
        port = record['port'] if running else self.ports[name]
        url = f'http://127.0.0.1:{port}' + ('/api/novels' if name == 'backend' else '/')
        ready = running and http_ready(url)
        return dict(running=running, ready=ready, port=port, url=url,
                    label='运行中' if ready else '启动中' if running else
                    '端口被其他程序占用' if port_busy(port) else '已停止')

    @contextlib.contextmanager
    def lock(self):
        with (self.directory / 'control.lock').open('a') as handle:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError('另一个控制台正在启停服务，请稍后再试')
            try:
                yield
            finally:
                fcntl.flock(handle, fcntl.LOCK_UN)

    def command(self, name):
        env = dict(self.env)
        if name == 'frontend':
            backend = self.record('backend')
            if self.owned(backend) and backend['port'] != self.ports['backend']:
                raise RuntimeError('backend 仍运行在旧端口，请先关闭后端，再按新配置启动')
            npm = shutil.which('npm', path=env.get('PATH'))
            if not npm:
                raise RuntimeError('找不到 npm，请安装 Node.js 22.12+ 并重新打开终端')
            if not (self.root / 'frontend-vue/node_modules/vite/bin/vite.js').exists():
                raise RuntimeError('前端依赖未安装：请先 cd frontend-vue && npm ci')
            target = f'http://127.0.0.1:{self.ports["backend"]}'
            # Vite prioritizes .env files over process env for this project's proxy.
            for filename in ('.env', '.env.local', '.env.development', '.env.development.local'):
                configured = read_env(self.root / 'frontend-vue' / filename).get('VITE_API_PROXY_TARGET')
                if configured and configured.rstrip('/') not in (target, target.replace('127.0.0.1', 'localhost')):
                    raise RuntimeError(f'frontend-vue/{filename} 的代理目标与控制台后端端口不同，请先调整')
            env['VITE_API_PROXY_TARGET'] = target
            return [npm, 'run', 'dev', '--', '--host', '127.0.0.1', '--port',
                    str(self.ports[name]), '--strictPort'], env
        maven = env.get('MVN') or shutil.which('mvn', path=env.get('PATH'))
        bundled = Path('/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn')
        if not maven and bundled.is_file():
            maven = str(bundled)
        if not maven:
            raise RuntimeError('找不到 Maven；请安装或在 .dev.env 设置 MVN=/完整路径/mvn')
        if not env.get('JAVA_HOME') and sys.platform == 'darwin':
            found = subprocess.run(['/usr/libexec/java_home', '-v', '17'], capture_output=True, text=True)
            if found.returncode == 0:
                env['JAVA_HOME'] = found.stdout.strip()
        env.update(SERVER_PORT=str(self.ports[name]), SERVER_ADDRESS='127.0.0.1')
        return [maven, '-B', '-Dstyle.color=never', 'spring-boot:run'], env

    def _start(self, name):
        previous = self.record(name)
        if self.owned(previous):
            if previous['port'] != self.ports[name]:
                raise RuntimeError(f'{name} 仍运行在旧端口，请先关闭该服务，再按新配置启动')
            return f'{name} 已在运行'
        if port_busy(self.ports[name]):
            raise RuntimeError(f'{name} 端口 {self.ports[name]} 已被占用，未启动或停止任何外部进程')
        command, env = self.command(name)
        token = uuid.uuid4().hex
        env['INK_DEV_COMMAND_JSON'] = json.dumps(command)
        env['INK_DEV_CWD'] = str(self.root / ('backend' if name == 'backend' else 'frontend-vue'))
        ready_read, ready_write = os.pipe()
        env['INK_DEV_READY_FD'] = str(ready_write)
        process = None
        phase = '创建启动进程'
        try:
            with self.log_path(name).open('ab') as log:
                log.write(f'\n--- {time.strftime("%Y-%m-%d %H:%M:%S")} 启动 {name} ---\n'.encode())
                log.flush()
                try:
                    process = subprocess.Popen([sys.executable, str(SCRIPT), '_worker', name, token],
                        cwd=self.root, env=env, stdin=subprocess.DEVNULL, stdout=log,
                        stderr=subprocess.STDOUT, start_new_session=True, pass_fds=(ready_write,))
                finally:
                    os.close(ready_write)
                    ready_write = None
            phase = '等待启动进程就绪'
            wait_worker_ready(process, ready_read, token, min(10, self.timeout))
            phase = '确认启动进程身份'
            identity_deadline = time.monotonic() + min(2, self.timeout)
            while True:
                info = process_info(process.pid)
                if (info and info['group'] == process.pid
                        and f'_worker {name} {token}' in info['command']):
                    break
                if process.poll() is not None or time.monotonic() >= identity_deadline:
                    raise RuntimeError(f'{name} 启动失败，未能确认本次进程身份（退出码 {process.returncode}）')
                time.sleep(.05)
            record = dict(pid=process.pid, born=info['born'], token=token,
                          name=name, port=self.ports[name])
            path = self.directory / f'{name}.json'
            temp = path.with_suffix('.tmp')
            temp.write_text(json.dumps(record))
            temp.replace(path)
            phase = '等待服务 HTTP 就绪'
            deadline = time.monotonic() + self.timeout
            identity_missing_since = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f'{name} 启动失败（退出码 {process.returncode}）；请查看 {self.log_path(name)}')
                if not self.owned(record):
                    identity_missing_since = identity_missing_since or time.monotonic()
                    if time.monotonic() - identity_missing_since >= 2:
                        raise RuntimeError(f'{name} 启动进程身份持续无法确认；请查看 {self.log_path(name)}')
                    time.sleep(.1)
                    continue
                identity_missing_since = None
                if self.status(name)['ready']:
                    return f'{name} 已启动，端口 {self.ports[name]}'
                time.sleep(.3)
            raise RuntimeError(f'{name} 启动超时，已清理本次进程；请查看日志（数据库需已运行）')
        except BaseException as error:
            with contextlib.suppress(OSError):
                with self.log_path(name).open('a') as log:
                    log.write(f'\n[控制台] {phase}失败：{error or type(error).__name__}\n')
            # This unreaped Popen handle is authoritative even if writing the
            # ownership record failed. Never infer ownership from an open port.
            if process is not None and process.poll() is None:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    with contextlib.suppress(ProcessLookupError):
                        os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=2)
            raise
        finally:
            os.close(ready_read)
            if ready_write is not None:
                os.close(ready_write)

    def start(self, name):
        with self.lock():
            return self._start(name)

    def start_all(self):
        with self.lock():
            started = []
            try:
                for name in NAMES:
                    existing = self.owned(self.record(name))
                    self._start(name)
                    if not existing:
                        started.append(name)
            except BaseException:
                for name in reversed(started):
                    self._stop(name)
                raise
        return '前后端已启动'

    def _stop(self, name):
        record = self.record(name)
        if not self.owned(record):
            return f'{name} 未由此控制台运行，无需关闭'
        with contextlib.suppress(ProcessLookupError):
            os.killpg(record['pid'], signal.SIGTERM)
        deadline = time.monotonic() + 10
        while self.owned(record) and time.monotonic() < deadline:
            time.sleep(.15)
        if self.owned(record):
            with contextlib.suppress(ProcessLookupError):
                os.killpg(record['pid'], signal.SIGKILL)
        return f'{name} 已关闭'

    def stop(self, name):
        with self.lock():
            return self._stop(name)

    def stop_all(self):
        with self.lock():
            return '; '.join(self._stop(name) for name in reversed(NAMES))


def worker():
    """A stable session leader survives Maven/npm wrapper exit until children stop."""
    stopping = False

    def terminate(*_):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    ready_fd = os.environ.pop('INK_DEV_READY_FD', None)
    if ready_fd is not None:
        with os.fdopen(int(ready_fd), 'wb', buffering=0) as channel:
            channel.write((sys.argv[3] + '\n').encode())
    print('[控制台] 启动进程已就绪，正在运行服务命令。', flush=True)
    child = subprocess.Popen(json.loads(os.environ.pop('INK_DEV_COMMAND_JSON')),
                             cwd=os.environ.pop('INK_DEV_CWD'), stdin=subprocess.DEVNULL)
    while child.poll() is None and not stopping:
        time.sleep(.2)
    os.killpg(os.getpgrp(), signal.SIGTERM)
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        child.poll()  # Reap our direct child, including the Maven/npm wrapper.
        scan = subprocess.Popen(['ps', '-eo', 'pid=,pgid=,stat='], stdout=subprocess.PIPE, text=True)
        output, _ = scan.communicate()
        others = [row for row in (line.split() for line in output.splitlines())
                  if len(row) == 3 and row[1] == str(os.getpgrp())
                  and row[0] not in (str(os.getpid()), str(scan.pid)) and not row[2].startswith('Z')]
        if not others:
            print(f'\n服务退出，退出码 {child.poll()}', flush=True)
            return
        time.sleep(.2)
    os.killpg(os.getpgrp(), signal.SIGKILL)


def logs(manager, name, follow):
    names = NAMES if name == 'all' else (name,)
    handles = {}
    try:
        for item in names:
            path = manager.log_path(item)
            print(f'--- {item}: {path} ---')
            if not path.exists():
                path.touch(mode=0o600)
            handle = path.open('rb')
            handle.seek(max(0, path.stat().st_size - 65536))
            print(handle.read().decode('utf-8', errors='replace'), end='')
            handles[item] = handle
        while follow:
            for item, handle in handles.items():
                data = handle.read()
                if data:
                    print(f'[{item}]\n' + data.decode('utf-8', errors='replace'), end='', flush=True)
            time.sleep(.3)
    finally:
        for handle in handles.values():
            handle.close()


def main():
    os.umask(0o077)
    if len(sys.argv) == 4 and sys.argv[1] == '_worker':
        worker()
        return
    parser = argparse.ArgumentParser(description='墨境本地控制台：不带参数打开双栏日志与启停菜单')
    parser.add_argument('action', nargs='?', default='ui', choices=['ui', 'start', 'stop', 'status', 'logs'])
    parser.add_argument('service', nargs='?', default='all', choices=['all', *NAMES])
    parser.add_argument('-f', '--follow', action='store_true', help='持续显示新日志，Ctrl+C 只退出查看')
    args = parser.parse_args()
    manager = Manager()
    if args.action == 'ui':
        if not sys.stdin.isatty() or not sys.stdout.isatty():
            parser.error('控制台需在终端打开；也可使用 start / stop / status / logs 命令')
        try:
            import dev_ui
            dev_ui.run(manager)
        except ImportError:
            raise RuntimeError('当前 Python 缺少 curses；仍可使用 start / stop / status / logs 命令')
    elif args.action == 'start':
        print(manager.start_all() if args.service == 'all' else manager.start(args.service))
        print(manager.frontend_url)
    elif args.action == 'stop':
        print(manager.stop_all() if args.service == 'all' else manager.stop(args.service))
    elif args.action == 'status':
        for name in NAMES if args.service == 'all' else (args.service,):
            status = manager.status(name)
            print(f'{name}: {status["label"]} · {status["port"]} · {manager.log_path(name)}')
    else:
        logs(manager, args.service, args.follow)


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('\n已退出控制台／日志查看。已有服务保持原状态。')
    except (RuntimeError, OSError, ValueError) as error:
        print(f'错误：{error}', file=sys.stderr)
        sys.exit(1)
