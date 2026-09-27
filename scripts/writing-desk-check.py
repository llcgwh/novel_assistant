#!/usr/bin/env python3
"""Exercise the creative desk over HTTP in integration-check.py's disposable database.

    python3 scripts/writing-desk-check.py /path/to/run.json

Creates its own small works and leaves them available for inspection. It never
changes the browser QA work, stops services, or prints connection credentials.
Uses the existing loopback WebDAV fixture by copying encrypted configuration
inside the marked test database; no credentials are decrypted in this script.
"""

import argparse
import copy
import datetime
import json
import os
import pathlib
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid


ROOT = pathlib.Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def uid():
    return str(uuid.uuid4())


def content(desk):
    return {key: value for key, value in desk.items() if key not in ("version", "mutationId")}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("state", type=pathlib.Path, help="run.json produced by integration-check.py --keep")
    args = parser.parse_args()
    state_path = args.state.resolve(strict=True)
    state = json.loads(state_path.read_text(encoding="utf-8"))
    database = state.get("database", "")
    require(bool(re.fullmatch(r"novel_it_[a-zA-Z0-9]+", database)), "Refusing a non-disposable database")
    run = pathlib.Path(state["directory"]).resolve(strict=True)
    require(state_path == run / "run.json", "State must belong to its original integration directory")
    require(run.name.startswith("novel-integration-") and (run / "dav").is_dir(), "Missing integration workspace")
    port = int(os.environ.get("IT_PORT", "8081"))
    require(1 <= port <= 65535, "Invalid loopback port")
    base = f"http://127.0.0.1:{port}/api"
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def api(method, path, data=None, expected=200):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
        request = urllib.request.Request(base + path, data=raw, method=method, headers={
            "Content-Type": "application/json", "Idempotency-Key": uid(),
        })
        try:
            with http.open(request, timeout=60) as response:
                status, body = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, body = error.code, error.read()
        require(status == expected, f"{method} {path}: expected HTTP {expected}, received {status}")
        return json.loads(body)

    marker = "Legacy integration " + database
    novels = api("GET", "/novels")
    require(any(novel["title"] == marker for novel in novels), "Wrong backend: disposable database marker missing")
    source = next((novel for novel in sorted(novels, key=lambda row: row["id"])
                   if novel["title"] == "持续优化验证" and novel.get("webdavServerUrl")), None)
    require(source is not None, "Run integration-check.py first to create the WebDAV fixture")
    dav_url = urllib.parse.urlparse(source.get("webdavServerUrl") or "")
    require(dav_url.scheme == "http" and dav_url.hostname == "127.0.0.1", "Refusing non-loopback WebDAV fixture")

    # Verify the SQL connection independently before copying existing encrypted settings.
    props = dict(line.split("=", 1) for line in (ROOT / "backend/src/main/resources/application.properties")
                 .read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith("#"))
    environment = os.environ.copy()
    environment["PGPASSWORD"] = os.environ.get("PGPASSWORD", props["spring.datasource.password"])
    pg = pathlib.Path(os.environ.get("PG_BIN", "/Library/PostgreSQL/18/bin"))
    pg_user = os.environ.get("PGUSER", props["spring.datasource.username"])

    def sql(command):
        result = subprocess.run([str(pg / "psql"), "-h", "localhost", "-U", pg_user, "-d", database,
                                 "-v", "ON_ERROR_STOP=1", "-Atc", command], env=environment,
                                capture_output=True, text=True)
        require(result.returncode == 0, "Disposable database SQL check failed; credentials were not printed")
        return result.stdout.strip()

    require(sql(f"SELECT EXISTS (SELECT 1 FROM novels WHERE title='{marker}')") == "t", "SQL database marker missing")
    source_id = int(source["id"])
    require(sql(f"SELECT webdav_password LIKE 'enc:v1:%' FROM novels WHERE id={source_id}") == "t",
            "WebDAV test fixture must use encrypted credentials")

    suffix = uid()[:8]
    origin = api("POST", "/novels", {"title": "创作桌 HTTP 验证 " + suffix})["id"]
    restored = api("POST", "/novels", {"title": "创作桌恢复验证 " + suffix})["id"]
    foreign = api("POST", "/novels", {"title": "创作桌隔离验证 " + suffix})["id"]
    prefix = f"/novels/{origin}"
    chapter_uid, block_id = uid(), uid()
    chapter = api("POST", prefix + "/writing/chapters", {
        "uid": chapter_uid, "title": "灯塔来信",
        "doc": {"type": "doc", "content": [{"type": "paragraph", "attrs": {"id": block_id},
                 "content": [{"type": "text", "text": "潮声穿过灯塔。"}]}]},
    })
    foreign_chapter = api("POST", f"/novels/{foreign}/writing/chapters", {"uid": uid(), "title": "另一本书"})
    empty = api("GET", prefix + "/writing/desk")
    require(content(empty) == {"nextPen": None, "tasks": [], "bookmarks": []}, "New work has nonempty desk")
    moment = datetime.datetime.now(datetime.timezone.utc).isoformat()
    anchor = {"chapterUid": chapter_uid, "blockId": block_id, "excerpt": "潮声穿过灯塔。"}
    request = {
        "version": empty["version"], "mutationId": uid(),
        "nextPen": {**anchor, "nextScene": "明天打开来信", "question": "寄信者是谁？", "opening": "铜灯亮了。", "updatedAt": moment},
        "tasks": [{**anchor, "uid": uid(), "body": "让对白更符合守塔人的身份", "category": "dialogue",
                   "priority": "high", "status": "open", "createdAt": moment, "updatedAt": moment}],
        "bookmarks": [{**anchor, "uid": uid(), "label": "回读开场", "createdAt": moment}],
    }
    sequence = api("GET", prefix + "/writing")["changeSequence"]
    saved = api("PUT", prefix + "/writing/desk", request)
    require(saved["version"] == empty["version"] + 1, "Desk revision did not advance")
    require(api("GET", prefix + "/writing")["changeSequence"] == sequence + 1, "Desk save did not mark the work dirty")
    require(api("PUT", prefix + "/writing/desk", request) == saved, "Exact retry changed desk data")
    require(api("GET", prefix + "/writing")["changeSequence"] == sequence + 1, "Retry counted as another mutation")
    stale = {**copy.deepcopy(request), "mutationId": uid()}
    api("PUT", prefix + "/writing/desk", stale, expected=409)
    reused = copy.deepcopy(request)
    reused["tasks"][0]["body"] = "不能借重试身份覆盖新正文"
    api("PUT", prefix + "/writing/desk", reused, expected=409)
    outsider = {**copy.deepcopy(saved), "mutationId": uid()}
    outsider["tasks"][0]["chapterUid"] = foreign_chapter["uid"]
    api("PUT", prefix + "/writing/desk", outsider, expected=400)
    require(api("GET", prefix + "/writing/desk") == saved, "Rejected requests mutated the desk")
    require(content(api("GET", f"/novels/{foreign}/writing/desk")) == content(empty), "Desk leaked between works")
    require(api("GET", prefix + "/writing")["chapters"][0]["wordCount"] == chapter["wordCount"], "Notes changed prose counts")

    backup = api("GET", prefix + "/backup")
    require(content(backup["data"]["writing"]["desk"]) == content(saved), "Complete backup omitted desk data")
    require("mutationId" not in backup["data"]["writing"]["desk"], "Backup leaked retry identity")
    api("POST", f"/novels/{restored}/backup", backup)
    restored_desk = api("GET", f"/novels/{restored}/writing/desk")
    require(content(restored_desk) == content(saved), "Restored notes, bookmarks or next pen changed")
    require(api("GET", f"/novels/{restored}/writing/chapters/{chapter_uid}")["doc"] == chapter["doc"], "Restore lost anchored chapter")
    legacy = copy.deepcopy(backup)
    del legacy["data"]["writing"]["desk"]
    blocked = api("POST", prefix + "/backup", legacy, expected=400)
    require("旧备份不含创作便签" in blocked.get("message", ""), "Missing legacy restore explanation")
    require(api("GET", prefix + "/writing/desk") == saved, "Legacy restore cleared creative notes")
    require(api("GET", prefix + "/writing/chapters/" + chapter_uid) == chapter, "Blocked restore modified manuscript")

    sql(f"UPDATE novels SET webdav_server_url=s.webdav_server_url, webdav_username=s.webdav_username, "
        f"webdav_password=s.webdav_password, webdav_auto_sync=false FROM "
        f"(SELECT * FROM novels WHERE id={source_id}) s WHERE novels.id={int(origin)}")
    first = api("POST", prefix + "/writing/cloud/push", {})
    workspace = api("GET", prefix + "/writing")
    cloud_folder = run / "dav" / "novel-backups" / ("ink-" + workspace["uid"])

    def cloud_file(name):
        require(pathlib.Path(name).name == name, "Unexpected remote filename")
        path = cloud_folder / name
        require(path.is_file(), "WebDAV did not write the expected local file")
        return json.loads(path.read_text(encoding="utf-8"))

    require(content(cloud_file(first["file"])["bundle"]["data"]["writing"]["desk"]) == content(saved), "Actual WebDAV file lacks creative data")
    require(workspace["syncedSequence"] == workspace["changeSequence"], "Successful upload did not acknowledge desk changes")
    update = {**copy.deepcopy(saved), "mutationId": uid()}
    update["tasks"][0]["status"] = "done"
    changed = api("PUT", prefix + "/writing/desk", update)
    pending = api("GET", prefix + "/writing")
    require(pending["changeSequence"] == workspace["changeSequence"] + 1 and
            pending["syncedSequence"] == workspace["syncedSequence"], "New desk edit is not awaiting synchronization")
    second = api("POST", prefix + "/writing/cloud/push", {})
    require(second["file"] != first["file"], "Desk-only edit did not create an immutable remote revision")
    require(content(cloud_file(second["file"])["bundle"]["data"]["writing"]["desk"]) == content(changed), "Second cloud revision contains stale desk data")
    final = api("GET", prefix + "/writing")
    require(final["syncedSequence"] == final["changeSequence"], "Final upload acknowledgement is stale")
    result = {"status": "passed", "database": database, "novelId": origin, "restoredNovelId": restored,
              "foreignNovelId": foreign, "chapterUid": chapter_uid, "bookUid": final["uid"],
              "checks": ["HTTP idempotency", "409 conflict", "cross-work rejection", "backup round trip",
                         "legacy backup protection", "actual WebDAV files", "desk-only sync sequence"],
              "cloudFiles": [first["file"], second["file"]]}
    result_path = run / "writing-desk-result.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: HTTP desk retries/conflicts/isolation, complete backup round trip, protected legacy restore, real WebDAV revisions")
    print("Result:", result_path)
    print("Fixture works:", origin, restored, foreign, "— existing browser fixture and services remain unchanged")


if __name__ == "__main__":
    main()
