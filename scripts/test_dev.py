"""Isolated launcher lifecycle checks; uses only temporary fake HTTP services."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('ink_dev', Path(__file__).with_name('dev.py'))
dev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dev)

FAKE_SERVICE = '''
import http.server, json, os, pathlib, subprocess, sys, time
mode, port, marker = sys.argv[1:]
if mode == 'fail':
    raise SystemExit(19)
if mode in ('tree', 'wrapper-exit'):
    child = subprocess.Popen([sys.executable, __file__, 'serve', port, marker + '.child'])
    pathlib.Path(marker).write_text(json.dumps({'pid': os.getpid(), 'child': child.pid}))
    if mode == 'wrapper-exit':
        time.sleep(.15)
        raise SystemExit(23)
    while True:
        time.sleep(1)
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'[]')
    def log_message(self, *_):
        pass
server = http.server.HTTPServer(('127.0.0.1', int(port)), Handler)
pathlib.Path(marker).write_text(json.dumps({'pid': os.getpid()}))
server.serve_forever()
'''


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def eventually(check, timeout=5):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return True
        time.sleep(.04)
    return check()


class FakeManager(dev.Manager):
    def __init__(self, root):
        super().__init__(root)
        self.modes = {'backend': 'serve', 'frontend': 'serve'}

    def command(self, name):
        return [sys.executable, str(self.root / 'fake_service.py'), self.modes[name],
                str(self.ports[name]), str(self.root / f'{name}.pid')], dict(self.env)


class LauncherLifecycleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ink-launcher-test-')
        self.root = Path(self.temp.name)
        (self.root / 'backend').mkdir()
        (self.root / 'frontend-vue').mkdir()
        (self.root / 'fake_service.py').write_text(FAKE_SERVICE)
        backend, frontend = free_port(), free_port()
        while frontend == backend:
            frontend = free_port()
        self.environment = mock.patch.dict(os.environ, {
            'INK_BACKEND_PORT': str(backend), 'INK_FRONTEND_PORT': str(frontend),
            'INK_START_TIMEOUT': '3',
        })
        self.environment.start()
        self.manager = FakeManager(self.root)
        self.processes = []
        real_popen = subprocess.Popen

        def tracked_popen(*args, **kwargs):
            process = real_popen(*args, **kwargs)
            if kwargs.get('start_new_session'):
                self.processes.append(process)
            return process

        self.popen_patch = mock.patch.object(dev.subprocess, 'Popen', side_effect=tracked_popen)
        self.popen_patch.start()

    def tearDown(self):
        try:
            self.manager.stop_all()
        finally:
            for process in self.processes:
                if process.poll() is None:
                    with contextlib.suppress(ProcessLookupError):
                        os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=3)
            self.popen_patch.stop()
            self.environment.stop()
            self.temp.cleanup()

    def assert_stopped(self, name):
        self.assertTrue(eventually(lambda: not dev.port_busy(self.manager.ports[name])))
        self.assertFalse(self.manager.status(name)['running'])

    def test_repeated_start_and_stop_after_exiting_log_view(self):
        self.manager.start('backend')
        record = self.manager.record('backend')
        self.assertIn('已在运行', self.manager.start('backend'))
        self.assertEqual(self.manager.record('backend'), record)
        with contextlib.redirect_stdout(io.StringIO()), mock.patch.object(dev.time, 'sleep', side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                dev.logs(self.manager, 'backend', True)
        self.assertTrue(self.manager.status('backend')['ready'])
        # A newly opened console can still stop services after the original closes.
        reopened = FakeManager(self.root)
        reopened.stop('backend')
        self.assert_stopped('backend')
        self.assertIn('无需关闭', reopened.stop('backend'))

    def test_stop_removes_wrapper_and_same_group_child(self):
        self.manager.modes['backend'] = 'tree'
        self.manager.start('backend')
        tree = json.loads((self.root / 'backend.pid').read_text())
        self.manager.stop('backend')
        self.assert_stopped('backend')
        for pid in tree.values():
            self.assertTrue(eventually(lambda: dev.process_info(pid) is None))

    def test_wrapper_exit_cleans_its_child_and_reports_start_failure(self):
        self.manager.modes['backend'] = 'wrapper-exit'
        # Delay readiness beyond wrapper lifetime to exercise failed-start cleanup.
        with mock.patch.object(dev, 'http_ready', return_value=False):
            with self.assertRaisesRegex(RuntimeError, '启动失败'):
                self.manager.start('backend')
        self.assert_stopped('backend')
        tree = json.loads((self.root / 'backend.pid').read_text())
        for pid in tree.values():
            self.assertTrue(eventually(lambda: dev.process_info(pid) is None))

    def test_foreign_port_is_not_adopted_or_stopped(self):
        with socket.socket() as foreign:
            foreign.bind(('127.0.0.1', self.manager.ports['backend']))
            foreign.listen()
            with self.assertRaisesRegex(RuntimeError, '已被占用'):
                self.manager.start('backend')
            self.manager.stop('backend')
            self.assertTrue(dev.port_busy(self.manager.ports['backend']))
            self.assertEqual(self.processes, [])

    def test_start_all_failure_rolls_back_only_services_it_started(self):
        self.manager.modes['frontend'] = 'fail'
        with self.assertRaisesRegex(RuntimeError, '启动失败'):
            self.manager.start_all()
        self.assert_stopped('backend')
        self.assert_stopped('frontend')
        self.manager.start('backend')
        previous = self.manager.record('backend')
        with self.assertRaisesRegex(RuntimeError, '启动失败'):
            self.manager.start_all()
        self.assertEqual(self.manager.record('backend'), previous)
        self.assertTrue(self.manager.status('backend')['ready'])

    def test_port_configuration_change_requires_explicit_stop(self):
        self.manager.start('backend')
        changed_port = free_port()
        while changed_port in self.manager.ports.values():
            changed_port = free_port()
        with mock.patch.dict(os.environ, {'INK_BACKEND_PORT': str(changed_port)}):
            changed = FakeManager(self.root)
            with self.assertRaisesRegex(RuntimeError, '旧端口'):
                changed.start('backend')
            with self.assertRaisesRegex(RuntimeError, '旧端口'):
                dev.Manager.command(changed, 'frontend')
        self.assertTrue(self.manager.status('backend')['ready'])

    def test_failed_identity_or_record_write_does_not_leave_spawned_worker(self):
        for fault in ('identity', 'record'):
            with self.subTest(fault=fault):
                patch = (mock.patch.object(dev, 'process_info', return_value=None)
                         if fault == 'identity' else
                         mock.patch.object(Path, 'write_text', side_effect=OSError('disk full')))
                with patch, self.assertRaises((RuntimeError, OSError)):
                    self.manager.start('backend')
                self.assertTrue(all(p.poll() is not None for p in self.processes))
                self.assert_stopped('backend')


if __name__ == '__main__':
    unittest.main()
