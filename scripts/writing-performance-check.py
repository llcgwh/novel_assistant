#!/usr/bin/env python3
"""Measure a synthetic long manuscript in integration-check.py's disposable database.

    python3 scripts/integration-check.py --keep
    python3 scripts/writing-performance-check.py /path/to/run.json [--keep]

Defaults to 120 chapters of 8,000 Chinese characters (960,000 total). Uses only
stdlib and the actual HTTP API. Never accepts an arbitrary API host or a database
without the integration marker. Results are observations, not speed guarantees.
The generated novel is removed unless --keep is requested; the original integration
services and fixtures are left alone. Synthetic sessions support heatmap browser QA.
"""

import argparse
import datetime
import http.client
import json
import math
import os
import pathlib
import re
import statistics
import sys
import time
import urllib.parse
import urllib.error
import urllib.request
import uuid


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def uid():
    return str(uuid.uuid4())


def chapter_marker(number):
    digits = "零一二三四五六七八九"
    return "长篇核验第" + "".join(digits[int(digit)] for digit in str(number)) + "章专属锚点"


def manuscript(number, words):
    # Known all-Han prose plus punctuation gives an exact expected word count.
    seed = "海风穿过石阶沈雾提灯走向古老的塔楼远处的潮声渐渐停下窗外星河映着归来的航船故事仍在夜色中继续"
    text = chapter_marker(number) + seed * math.ceil(words / len(seed))
    text = text[:words]
    return {
        "type": "doc",
        "content": [
            {
                "type": "paragraph",
                "attrs": {"id": uid()},
                "content": [{"type": "text", "text": text[start:start + 100] + "。"}],
            }
            for start in range(0, words, 100)
        ],
    }


def word_count(doc):
    text = "".join(child["text"] for paragraph in doc["content"] for child in paragraph["content"])
    return sum("\u4e00" <= character <= "\u9fff" for character in text)


def measurements(rows):
    elapsed = [row["elapsedMs"] for row in rows]
    return {
        "runs": rows,
        "medianMs": round(statistics.median(elapsed), 2),
        "minMs": round(min(elapsed), 2),
        "maxMs": round(max(elapsed), 2),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("state", type=pathlib.Path, help="run.json produced by integration-check.py --keep")
    parser.add_argument("--keep", action="store_true", help="keep this synthetic novel for browser QA")
    parser.add_argument("--chapters", type=int, default=120)
    parser.add_argument("--words", type=int, default=8000, help="Chinese characters per chapter")
    parser.add_argument("--samples", type=int, default=3)
    args = parser.parse_args()
    require(2 <= args.chapters <= 500, "Use 2–500 chapters")
    require(100 <= args.words <= 50000, "Use 100–50,000 characters per chapter")
    require(1 <= args.samples <= 10, "Use 1–10 measurement samples")

    state_path = args.state.resolve(strict=True)
    state = json.loads(state_path.read_text(encoding="utf-8"))
    database = state.get("database", "")
    require(bool(re.fullmatch(r"novel_it_[a-zA-Z0-9]+", database)), "Refusing a non-disposable database")
    run = pathlib.Path(state["directory"]).resolve(strict=True)
    require(state_path == run / "run.json", "State must belong to its original integration directory")
    require(run.name.startswith("novel-integration-") and (run / "dav").is_dir(), "Missing integration directory marker")
    require(bool(state.get("pids")), "Missing owned integration process IDs")
    port = int(os.environ.get("IT_PORT", "8081"))
    require(1 <= port <= 65535, "Invalid integration port")
    base = f"http://127.0.0.1:{port}/api"
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(method, path, data=None, binary=False):
        payload = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
        message = urllib.request.Request(base + path, data=payload, method=method, headers={
            "Content-Type": "application/json", "Idempotency-Key": uid(),
        })
        for attempt in range(6):
            started = time.perf_counter()
            try:
                with http.open(message, timeout=180) as response:
                    body = response.read()
                break
            except (urllib.error.URLError, ConnectionError, http.client.RemoteDisconnected) as error:
                if isinstance(error, urllib.error.HTTPError) and error.code not in (502, 503, 504):
                    raise
                if attempt == 5:
                    raise
                # Same mutation IDs make chapter/session writes safe to retry. Novel
                # creation has no such key: recover its unique fixture before retrying.
                time.sleep(attempt + 1)
                if method == "POST" and path == "/novels":
                    novels, _ = request("GET", "/novels")
                    recovered = next((novel for novel in novels if novel["title"] == data["title"]), None)
                    if recovered:
                        return recovered, {"elapsedMs": 0, "responseBytes": 0, "recoveredCreation": True}
        timing = {"elapsedMs": round((time.perf_counter() - started) * 1000, 2), "responseBytes": len(body), "retries": attempt}
        return (body if binary else json.loads(body) if body else None), timing

    def verify_marker():
        novels, _ = request("GET", "/novels")
        require(any(novel["title"] == "Legacy integration " + database for novel in novels),
                "Wrong backend: disposable database marker is absent; no writes allowed")

    # Read-only checks above must all succeed before the first mutation.
    verify_marker()
    timestamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    title = f"长篇性能验证 · 虚构书稿 {timestamp} {uuid.uuid4().hex[:6]}"
    tag = "Synthetic writing performance fixture; " + database
    result = {
        "status": "running", "database": database, "apiBase": base,
        "startedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "synthetic": True, "chapterCount": args.chapters, "wordsPerChapter": args.words,
        "expectedWords": args.chapters * args.words, "kept": args.keep,
        "measurementNote": "Local HTTP request plus response transfer, excluding client JSON parsing. Final successful attempt only; retries recorded. Search/export have an excluded warm-up. No speed threshold or cross-machine guarantee.",
        "calendarNote": "Synthetic historical session samples for visual QA only; generated chapter imports are not real typing sessions.",
    }
    output = run / "writing-performance-result.json"
    novel_id = None
    try:
        novel, _ = request("POST", "/novels", {
            "title": title,
            "description": "仅用于独立测试库的近百万字性能与日历验收，正文及历史码字记录均为合成数据。 " + tag,
        })
        novel_id = novel["id"]
        prefix = f"/novels/{novel_id}/writing"
        result.update(novelId=novel_id, title=title)
        chapters = []
        creation_started = time.perf_counter()
        for number in range(1, args.chapters + 1):
            body = manuscript(number, args.words)
            require(word_count(body) == args.words, "Synthetic source count is invalid")
            chapter, _ = request("POST", prefix + "/chapters", {
                "uid": uid(), "title": f"第{number}章 潮声与灯塔", "doc": body,
                "summary": "虚构性能样本 · " + chapter_marker(number), "links": [],
            })
            require(chapter["wordCount"] == args.words, "Server chapter count differs from synthetic source")
            plain = "".join(paragraph["content"][0]["text"] + "\n" for paragraph in body["content"])
            chapters.append({"uid": chapter["uid"], "marker": chapter_marker(number), "plain": plain})
            if number % 30 == 0 or number == args.chapters:
                print(f"Created {number}/{args.chapters} synthetic chapters", flush=True)
        result["fixtureCreationMs"] = round((time.perf_counter() - creation_started) * 1000, 2)
        result["firstChapterUid"] = chapters[0]["uid"]
        result["browserPath"] = f"/novel/{novel_id}/writing?chapter={chapters[0]['uid']}"

        workspace, _ = request("GET", prefix)
        workspace["preferences"] = {**workspace.get("preferences", {}), "dailyGoal": 2000}
        request("PUT", prefix + "/preferences", workspace)
        date = datetime.date.today()
        session_count = 0
        for days_ago in range(365):
            if days_ago % 7 in (1, 4) or 140 <= days_ago <= 150:
                continue
            session_id = uid()
            net = [-120, 0, 160, 460, 780, 1240, 1780, 2260, 3100][(days_ago * 5 + days_ago // 7) % 9]
            typed = max(180, net + 85)
            request("PUT", prefix + "/sessions/" + session_id, {
                "uid": session_id, "sequence": 1, "date": (date - datetime.timedelta(days=days_ago)).isoformat(),
                "chapterUid": chapters[0]["uid"], "typed": typed, "pasted": 0, "net": net,
                "activeSeconds": max(300, int(typed / 32 * 60)), "peak": 45,
            })
            session_count += 1
        result["syntheticSessions"] = session_count

        # Workspace must contain summaries only; chapter bodies are loaded individually.
        rows = []
        request("GET", prefix)
        for _ in range(args.samples):
            workspace, timing = request("GET", prefix)
            rows.append(timing)
            require(len(workspace["chapters"]) == args.chapters, "Workspace chapter count differs")
            require(sum(chapter["wordCount"] for chapter in workspace["chapters"]) == result["expectedWords"], "Workspace total differs")
            require(all("doc" not in chapter and "document" not in chapter and "notes" not in chapter for chapter in workspace["chapters"]),
                    "Workspace unexpectedly includes full chapter bodies")
            require(len(workspace["sessions"]) == session_count, "Session fixture did not round-trip")
        result["workspace"] = measurements(rows)

        middle = chapters[len(chapters) // 2]
        path = prefix + "/chapters/" + middle["uid"]
        rows = []
        request("GET", path)
        for _ in range(args.samples):
            chapter, timing = request("GET", path)
            rows.append(timing)
            require(chapter["wordCount"] == args.words and word_count(chapter["doc"]) == args.words, "Single chapter read differs")
        result["chapterRead"] = measurements(rows)
        original_doc = json.loads(json.dumps(chapter["doc"]))
        rows = []
        for index in range(args.samples):
            # Actual manuscript edit, not a metadata-only save; restore source afterward.
            chapter["doc"]["content"][-1]["content"][-1]["text"] += "修订检验"
            chapter["mutationId"] = uid()
            chapter, timing = request("PUT", path, chapter)
            rows.append(timing)
            require(chapter["wordCount"] == args.words + 4 * (index + 1), "Saved edit count differs")
        result["chapterSave"] = measurements(rows)
        chapter.update(doc=original_doc, mutationId=uid())
        restored, _ = request("PUT", path, chapter)
        require(restored["wordCount"] == args.words, "Post-measurement manuscript restoration failed")

        rows = []
        request("GET", prefix + "/search?q=" + urllib.parse.quote(middle["marker"]))
        for _ in range(args.samples):
            found, timing = request("GET", prefix + "/search?q=" + urllib.parse.quote(middle["marker"]))
            rows.append(timing)
            require(len(found) == 1 and found[0]["uid"] == middle["uid"], "Unique full-book search matched the wrong chapter")
            require(middle["marker"] in found[0]["excerpt"], "Search excerpt omitted the matched text")
        result["fullBookSearch"] = measurements(rows)

        rows = []
        request("POST", prefix + "/export", {"format": "txt", "includeNotes": False}, binary=True)
        for _ in range(args.samples):
            exported, timing = request("POST", prefix + "/export", {"format": "txt", "includeNotes": False}, binary=True)
            rows.append(timing)
            text = exported.decode("utf-8-sig")
            require(all(text.count(chapter["marker"]) == 1 for chapter in chapters), "Whole-book export is missing or duplicating chapter content")
            positions = [text.index(chapter["marker"]) for chapter in chapters]
            require(positions == sorted(positions), "Whole-book export order differs from chapter order")
            require(all(text.startswith(chapter["plain"], position) for chapter, position in zip(chapters, positions)), "Whole-book export changed or truncated manuscript content")
            require("修订检验" not in text, "Export contains a temporary measurement edit")
        result["fullBookTxtExport"] = measurements(rows)
        result["workspaceToTxtSizeRatio"] = round(result["workspace"]["runs"][0]["responseBytes"] / len(exported), 4)
        selected, _ = request("POST", prefix + "/export", {"format": "txt", "uids": [middle["uid"]]}, binary=True)
        selected_text = selected.decode("utf-8-sig")
        require(middle["plain"] in selected_text, "Selected export omitted or truncated target chapter")
        require(all(chapter["marker"] not in selected_text for chapter in chapters if chapter["uid"] != middle["uid"]), "Selected export leaked unselected content")
        summary, _ = request("GET", "/novels/writing-summary")
        require(summary[str(novel_id)]["words"] == result["expectedWords"], "Bookshelf summary differs from final manuscript")
        result["status"] = "passed"
    except BaseException as error:
        result["status"] = "failed"
        result["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        result["finishedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        if novel_id is not None and not args.keep:
            try:
                verify_marker()
                target, _ = request("GET", f"/novels/{novel_id}")
                require(target["title"] == title and tag in target.get("description", ""), "Refusing cleanup: fixture identity changed")
                request("DELETE", f"/novels/{novel_id}")
                result["fixtureRemoved"] = True
            except BaseException as error:
                result["cleanupError"] = f"{type(error).__name__}: {error}"
                if result["status"] == "passed":
                    result["status"] = "cleanup-failed"
                print("Fixture cleanup did not complete; inspect result JSON before retrying.", file=sys.stderr)
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Performance result:", output, flush=True)
    require(not result.get("cleanupError"), "Performance checks passed but synthetic fixture cleanup failed")
    print(f"PASS: {args.chapters} chapters, {result['expectedWords']:,} Chinese characters; body-free workspace, chapter read/save, search and TXT scope verified")
    for name in ("workspace", "chapterRead", "chapterSave", "fullBookSearch", "fullBookTxtExport"):
        print(f"  {name}: median {result[name]['medianMs']:.2f} ms; {result[name]['runs'][0]['responseBytes']:,} response bytes")
    if args.keep:
        print(f"Kept synthetic novel {novel_id}: {result['browserPath']}")


if __name__ == "__main__":
    main()
