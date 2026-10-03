"""Standard-library terminal UI for dev.py's service manager.

ImportError from curses is intentionally left to dev.py so it can offer its CLI.
Closing this console never stops services; X is the explicit stop-all command.
"""

from __future__ import annotations

import curses
import queue
import re
import subprocess
import sys
import threading
import time
import unicodedata
import webbrowser
from pathlib import Path


_SERVICES = ("backend", "frontend")
_LABELS = {"backend": "后端", "frontend": "前端"}
_ANSI = re.compile(r"\x1b\].*?(?:\x07|\x1b\\)|\x1b\[[0-?]*[ -/]*[@-~]|\x1b[@-_]")
_CONTROLS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


def _width(char: str) -> int:
    if unicodedata.combining(char) or unicodedata.category(char) in ("Cf", "Mn", "Me"):
        return 0
    return 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1


def _fit(text: str, columns: int) -> str:
    result, used = [], 0
    for char in text:
        size = _width(char)
        if used + size > columns:
            break
        result.append(char)
        used += size
    return "".join(result)


def _clean(line: str) -> str:
    # Progress bars commonly overwrite the same line with carriage returns.
    return _CONTROLS.sub("", _ANSI.sub("", line).split("\r")[-1]).expandtabs(4)


def _wrap(line: str, columns: int) -> list[str]:
    columns = max(2, columns)
    rows, part, used = [], [], 0
    for char in line:
        size = _width(char)
        if used + size > columns:
            rows.append("".join(part))
            part, used = [], 0
        part.append(char)
        used += size
    rows.append("".join(part))
    return rows


class _Tail:
    def __init__(self, path):
        self.path = Path(path)
        self.signature = None
        self.lines: list[str] = []
        self.rows: list[str] = []
        self.columns = 0
        self.offset = 0
        self.error = ""
        self.dirty = True

    def poll(self):
        try:
            stat = self.path.stat()
            signature = (stat.st_ino, stat.st_size, stat.st_mtime_ns)
            if signature == self.signature:
                return
            with self.path.open("rb") as stream:
                start = max(0, stat.st_size - 65536)
                stream.seek(start)
                content = stream.read(65536)
            if start and b"\n" in content:
                # The first bytes can begin halfway through UTF-8 or a line.
                content = content.partition(b"\n")[2]
            self.lines = [_clean(line) for line in content.decode("utf-8", "replace").split("\n")][-1000:]
            if self.lines and not self.lines[-1]:
                self.lines.pop()
            self.signature, self.error, self.dirty = signature, "", True
        except FileNotFoundError:
            self.error = "等待服务写入日志…"
        except OSError as exc:
            self.error = f"无法读取日志：{exc}"

    def visible(self, columns: int, height: int) -> list[str]:
        if self.dirty or columns != self.columns:
            old_rows = self.rows
            self.rows = [row for line in self.lines for row in _wrap(line, columns)]
            if self.offset and self.dirty and old_rows and columns == self.columns:
                # Keep a paused view in place as new log lines arrive.
                anchor = old_rows[-8:]
                for end in range(len(self.rows), len(anchor) - 1, -1):
                    if self.rows[end - len(anchor):end] == anchor:
                        self.offset += len(self.rows) - end
                        break
            self.columns, self.dirty = columns, False
        self.offset = min(self.offset, max(0, len(self.rows) - height))
        end = max(0, len(self.rows) - self.offset)
        return self.rows[max(0, end - height):end]


class _Actions:
    """Exactly one worker processes each explicit operation, in sequence."""

    def __init__(self, manager):
        self.manager = manager
        self.jobs = queue.Queue()
        self.events = queue.Queue()
        self.busy = False
        self.thread = threading.Thread(target=self._work, name="dev-console-actions", daemon=True)
        self.thread.start()

    def submit(self, operations):
        if self.busy:
            return False
        self.busy = True
        self.jobs.put(operations)
        return True

    def _work(self):
        while True:
            operations = self.jobs.get()
            if operations is None:
                return
            failures = []
            for action, name in operations:
                verb = {"start": "启动", "stop": "关闭", "start_all": "启动全部服务", "stop_all": "关闭全部服务", "open": "打开网页", "logs": "打开日志目录"}[action]
                label = _LABELS.get(name, "")
                self.events.put(("progress", f"正在{verb}{label}…"))
                try:
                    if action == "open":
                        if not webbrowser.open(self.manager.frontend_url):
                            raise RuntimeError(f"请手动访问 {self.manager.frontend_url}")
                    elif action == "logs":
                        directory = Path(self.manager.log_path("frontend")).parent
                        directory.mkdir(parents=True, exist_ok=True)
                        if sys.platform == "darwin":
                            subprocess.run(["open", str(directory)], check=True, capture_output=True)
                        elif not webbrowser.open(directory.resolve().as_uri()):
                            raise RuntimeError(f"请手动打开 {directory}")
                    elif action in ("start_all", "stop_all"):
                        getattr(self.manager, action)()
                    else:
                        getattr(self.manager, action)(name)
                except Exception as exc:
                    failures.append(f"{verb}{label}：{exc}")
            self.events.put(("done", "；".join(failures) if failures else "操作完成。服务状态将自动更新。"))

    def close(self):
        self.jobs.put(None)
        self.thread.join(timeout=1)


def _put(screen, y, x, text, width, attr=0):
    rows, columns = screen.getmaxyx()
    if y < 0 or y >= rows or x < 0 or x >= columns:
        return
    try:
        screen.addstr(y, x, _fit(str(text), min(width, columns - x)), attr)
    except curses.error:
        # Some terminals report ERR after successfully writing the bottom-right cell.
        pass


def _pane(screen, rect, name, state, tail, active, colors):
    y, x, height, width = rect
    edge = colors["accent"] if active else curses.A_DIM
    _put(screen, y, x, "+" + "-" * max(0, width - 2) + "+", width, edge)
    _put(screen, y + height - 1, x, "+" + "-" * max(0, width - 2) + "+", width, edge)
    for row in range(y + 1, y + height - 1):
        _put(screen, row, x, "|", 1, edge)
        _put(screen, row, x + width - 1, "|", 1, edge)
    label = _LABELS[name]
    running, ready = state.get("running", False), state.get("ready", False)
    status = state.get("label") or ("已就绪" if ready and running else "运行中 · 等待就绪" if running else "已停止")
    color = colors["good"] if ready and running else colors["warn"] if running else curses.A_DIM
    title = f" {'> ' if active else ''}{label}  :{state.get('port', '?')} "
    _put(screen, y, x + 2, title, width - 4, edge | curses.A_BOLD)
    _put(screen, y + 1, x + 2, status, width - 4, color)
    _put(screen, y + 2, x + 2, state.get("url", ""), width - 4, curses.A_DIM)
    log_height = max(0, height - 5)
    rows = tail.visible(max(2, width - 4), log_height)
    for index, line in enumerate(rows):
        _put(screen, y + 3 + index, x + 2, line, width - 4)
    if not rows:
        _put(screen, y + 3, x + 2, tail.error or "暂无日志，按 S 启动服务。", width - 4, curses.A_DIM)
    elif tail.error:
        _put(screen, y + height - 2, x + 2, tail.error, width - 4, colors["warn"])
    mode = f" 向上 {tail.offset} 行 · End 回实时 " if tail.offset else " 实时日志 "
    _put(screen, y + height - 1, x + 2, mode, width - 4, edge)


def _main(screen, manager):
    try:
        curses.curs_set(0)
    except curses.error:
        pass
    screen.keypad(True)
    screen.timeout(100)
    colors = {"accent": curses.A_BOLD, "good": curses.A_BOLD, "warn": curses.A_BOLD}
    if curses.has_colors():
        curses.start_color()
        background = curses.COLOR_BLACK
        try:
            curses.use_default_colors()
            background = -1
        except curses.error:
            pass
        for index, (name, foreground) in enumerate((("accent", curses.COLOR_CYAN), ("good", curses.COLOR_GREEN), ("warn", curses.COLOR_YELLOW)), 1):
            curses.init_pair(index, foreground, background)
            colors[name] = curses.color_pair(index)
    tails = {name: _Tail(manager.log_path(name)) for name in _SERVICES}
    states = {name: {} for name in _SERVICES}
    actions = _Actions(manager)
    active, message = 0, "S 启动全部服务；Q 仅退出控制台，服务继续运行。"
    exiting, next_status, next_logs = False, 0.0, 0.0
    try:
        while True:
            now = time.monotonic()
            while True:
                try:
                    event, message = actions.events.get_nowait()
                    if event == "done":
                        actions.busy = False
                        next_status = 0
                except queue.Empty:
                    break
            if exiting and not actions.busy:
                break
            if now >= next_status:
                for name in _SERVICES:
                    try:
                        states[name] = manager.status(name)
                    except Exception as exc:
                        message = f"{_LABELS[name]}状态读取失败：{exc}"
                next_status = now + 1
            if now >= next_logs:
                for tail in tails.values():
                    tail.poll()
                next_logs = now + 0.4
            screen.erase()
            height, width = screen.getmaxyx()
            _put(screen, 0, 1, "墨境  /  开发控制台", width - 2, colors["accent"] | curses.A_BOLD)
            _put(screen, 1, 1, "后端与前端独立运行 · 退出控制台不会关闭服务", width - 2, curses.A_DIM)
            legend = [row for text in (
                "S 启动全部  X 关闭全部  B 后端切换  F 前端切换",
                "Tab 面板  ↑↓ 滚动  PgUp/PgDn 翻页  End 实时日志",
                "O 打开网页  L 日志目录  Q 退出控制台(服务继续运行)",
            ) for row in _wrap(text, max(2, width - 2))]
            footer_height = len(legend) + 1
            available = height - 3 - footer_height
            if width >= 100 and available >= 7:
                left = width // 2
                rects = [(3, 0, available, left), (3, left, available, width - left)]
            elif width >= 30 and available >= 14:
                upper = available // 2
                rects = [(3, 0, upper, width), (3 + upper, 0, available - upper, width)]
            else:
                rects = []
                _put(screen, 3, 1, "请扩大终端窗口以显示两组日志。", width - 2, colors["warn"])
            for index, rect in enumerate(rects):
                name = _SERVICES[index]
                _pane(screen, rect, name, states[name], tails[name], active == index, colors)
            hint = "操作完成后自动退出；服务继续运行。" if exiting else message
            _put(screen, height - footer_height, 1, hint, width - 2, colors["warn"] if actions.busy else curses.A_NORMAL)
            for index, line in enumerate(legend):
                _put(screen, height - len(legend) + index, 1, line, width - 2, colors["accent"] if index == 0 else curses.A_DIM)
            screen.refresh()
            try:
                key = screen.get_wch()
            except curses.error:
                continue
            except KeyboardInterrupt:
                key = "q"
            if isinstance(key, str):
                key = key.lower()
            if key in ("q", "\x03"):
                if actions.busy:
                    exiting = True
                else:
                    break
            elif key in ("\t", curses.KEY_BTAB):
                active = 1 - active
            elif key in (curses.KEY_UP, curses.KEY_PPAGE):
                tails[_SERVICES[active]].offset += 1 if key == curses.KEY_UP else max(1, available // 2)
            elif key in (curses.KEY_DOWN, curses.KEY_NPAGE):
                tail = tails[_SERVICES[active]]
                tail.offset = max(0, tail.offset - (1 if key == curses.KEY_DOWN else max(1, available // 2)))
            elif key == curses.KEY_END:
                tails[_SERVICES[active]].offset = 0
            elif key == curses.KEY_HOME:
                tails[_SERVICES[active]].offset = len(tails[_SERVICES[active]].rows)
            elif key in ("s", "x", "b", "f", "o", "l") and not exiting:
                if actions.busy:
                    message = "正在执行上一项操作，请等待完成后再操作。"
                    continue
                if key == "s":
                    operations = [("start_all", "")]
                elif key == "x":
                    operations = [("stop_all", "")]
                elif key == "o":
                    operations = [("open", "")]
                elif key == "l":
                    operations = [("logs", "")]
                else:
                    name = "backend" if key == "b" else "frontend"
                    try:
                        states[name] = manager.status(name)
                    except Exception as exc:
                        message = f"无法确认服务状态，未执行切换：{exc}"
                        continue
                    operations = [("stop" if states[name].get("running") else "start", name)]
                actions.submit(operations)
    finally:
        # The normal Q path waits until the current explicit action completes.
        # No stop/kill action is issued as part of console cleanup.
        actions.close()


def run(manager):
    """Display the console; manager owns all service lifecycle and log files."""
    curses.wrapper(_main, manager)
