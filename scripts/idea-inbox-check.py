#!/usr/bin/env python3
"""Verify C11 through real HTTP, disposable PostgreSQL, and loopback WebDAV.

    PGPORT=55432 python3 scripts/idea-inbox-check.py /path/to/run.json

Requires integration-check.py --keep to have created the fixture. Honors IT_PORT,
PG_BIN, PGUSER, PGPASSWORD, and PGPORT. Creates separate test works and leaves
them available for inspection. Does not start/stop services or touch browser QA
works. Copies encrypted WebDAV configuration within the marked test database;
this script never decrypts or prints credentials.
"""

import argparse
import copy
import datetime
import hashlib
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


def mutation(desk):
    return {**copy.deepcopy(desk), "mutationId": uid()}


def document(*paragraphs):
    return {"type": "doc", "content": [
        {"type": "paragraph", "attrs": {"id": uid()}, "content": [{"type": "text", "text": text}]}
        for text in paragraphs
    ]}


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
    works = {key: api("POST", "/novels", {"title": label + " " + suffix})["id"] for key, label in (
        ("origin", "灵感 HTTP 验证"), ("restored", "灵感备份恢复验证"), ("foreign", "灵感作品隔离验证"),
        ("legacy", "灵感旧包新作品验证"), ("cloud", "灵感第二设备验证"),
    )}
    prefix = f'/novels/{works["origin"]}'

    def chapter(novel, title, *paragraphs):
        return api("POST", f"/novels/{novel}/writing/chapters", {
            "uid": uid(), "title": title, "doc": document(*(paragraphs or (title,))),
        })

    first = chapter(works["origin"], "灯塔来信", "潮声穿过灯塔。", "守塔人拆开来信。", "纸上只有一轮月亮。")
    second = chapter(works["origin"], "旧航道", "黎明将至，旧航道浮出海面。")
    trashed = chapter(works["origin"], "待删章节", "这章的灵感来源应继续保留。")
    foreign = chapter(works["foreign"], "另一本书", "不属于当前作品。")
    empty = api("GET", prefix + "/writing/desk")
    require(empty.get("ideas") == [], "New desk must expose an empty ideas array")
    # An older client is still valid for an inbox without content.
    old_empty = mutation(empty)
    del old_empty["ideas"]
    empty = api("PUT", prefix + "/writing/desk", old_empty)
    require(empty["ideas"] == [], "Legacy empty inbox was not normalized")
    moment = datetime.datetime.now(datetime.timezone.utc).isoformat()
    body = "  月下的对白\n\n“下次潮退时，带上这枚罗盘。” 🌙\n尾行保留。\n"
    source_key = "legacy-notebook:" + hashlib.sha256(body.encode("utf-8")).hexdigest()
    idea = {"uid": uid(), "title": "旧便笺迁移", "body": body, "category": "dialogue",
            "chapterUids": [first["uid"], second["uid"]], "createdAt": moment, "updatedAt": moment,
            "sourceKey": source_key}
    request = mutation(empty)
    request["ideas"] = [idea, {"uid": uid(), "title": "回收站来源", "body": "保留被删除章节的关联。",
                                "category": "plot", "chapterUids": [trashed["uid"]],
                                "createdAt": moment, "updatedAt": moment}]
    sequence = api("GET", prefix + "/writing")["changeSequence"]
    saved = api("PUT", prefix + "/writing/desk", request)
    require(saved["ideas"] == request["ideas"], "Idea text, categories, multiple chapter links, or sourceKey changed")
    require(saved["version"] == empty["version"] + 1, "Inbox revision did not advance")
    require(api("GET", prefix + "/writing")["changeSequence"] == sequence + 1, "Inbox save did not mark work dirty")
    require(api("PUT", prefix + "/writing/desk", request) == saved, "Lost-response retry duplicated the migrated idea")
    require(api("GET", prefix + "/writing")["changeSequence"] == sequence + 1, "Retry counted as another mutation")
    api("PUT", prefix + "/writing/desk", mutation(request), expected=409)
    reused = copy.deepcopy(request)
    reused["ideas"][0]["body"] = "不能借重试身份覆盖原文。"
    api("PUT", prefix + "/writing/desk", reused, expected=409)

    def rejected(edit, label, expected=400):
        invalid = mutation(saved)
        edit(invalid)
        response = api("PUT", prefix + "/writing/desk", invalid, expected=expected)
        require(api("GET", prefix + "/writing/desk") == saved, label + " mutated the inbox")
        require(api("GET", prefix + "/writing")["changeSequence"] == sequence + 1, label + " changed sync sequence")
        return response

    rejected(lambda desk: desk["ideas"].append({**copy.deepcopy(idea), "uid": uid()}), "Duplicate migration source")
    rejected(lambda desk: desk["ideas"].append(copy.deepcopy(idea)), "Duplicate idea UID")
    rejected(lambda desk: desk["ideas"][0]["chapterUids"].append(first["uid"]), "Duplicate chapter link")
    rejected(lambda desk: desk["ideas"][0].update(category="invalid"), "Invalid category")
    rejected(lambda desk: desk["ideas"][0].update(body="字" * 20001), "Oversized idea body")
    isolated = rejected(lambda desk: desk["ideas"][0]["chapterUids"].append(foreign["uid"]), "Cross-work chapter")
    require("不属于当前作品" in isolated.get("message", ""), "Missing cross-work explanation")
    blocked = rejected(lambda desk: desk.pop("ideas"), "Legacy client", expected=409)
    require("未包含灵感收件箱" in blocked.get("message", ""), "Missing legacy-client explanation")
    require(api("GET", f'/novels/{works["foreign"]}/writing/desk')["ideas"] == [], "Inbox leaked between works")
    require(api("GET", prefix + "/writing/chapters/" + first["uid"]) == first, "Idea changes modified manuscript")

    split_request = {"revision": first["revision"], "index": 1, "uid": uid(), "title": "来信后半章"}
    split = api("POST", prefix + "/writing/chapters/" + first["uid"] + "/split", split_request)
    after_split = api("GET", prefix + "/writing/desk")
    require(after_split["ideas"][0]["chapterUids"] == [first["uid"], second["uid"], split["uid"]],
            "Split did not preserve old links and add the second half")
    require(after_split["ideas"][1] == saved["ideas"][1], "Split changed an unrelated idea")
    require(after_split["version"] == saved["version"] + 1 and "mutationId" not in after_split,
            "Split did not invalidate an older inbox editor")
    require(api("POST", prefix + "/writing/chapters/" + first["uid"] + "/split", split_request) == split,
            "Split retry changed its result")
    require(api("GET", prefix + "/writing/desk") == after_split, "Split retry duplicated chapter associations")
    api("PUT", prefix + "/writing/desk", mutation(saved), expected=409)
    left = api("GET", prefix + "/writing/chapters/" + first["uid"])
    merged = api("POST", prefix + "/writing/chapters/" + first["uid"] + "/merge", {
        "revision": left["revision"], "otherRevision": split["revision"], "otherUid": split["uid"],
    })
    saved = api("GET", prefix + "/writing/desk")
    require(saved["ideas"][0]["chapterUids"] == [first["uid"], second["uid"]], "Merge did not replace and deduplicate links")
    require(merged["doc"] == first["doc"] and merged["wordCount"] == first["wordCount"], "Split/merge changed prose")
    require(api("GET", prefix + "/writing/chapters/" + split["uid"])["deleted"], "Merged source did not enter trash")
    api("PUT", prefix + "/writing/chapters/" + trashed["uid"], {**trashed, "deleted": True, "mutationId": uid()})
    require(api("GET", prefix + "/writing/desk") == saved, "Chapter deletion discarded its inspiration")
    edit_trash_link = mutation(saved)
    edit_trash_link["ideas"][1]["title"] = "已删除章节的来源仍保留"
    saved = api("PUT", prefix + "/writing/desk", edit_trash_link)
    require(saved["ideas"][1]["chapterUids"] == [trashed["uid"]], "Editing an idea lost its deleted-chapter link")

    backup = api("GET", prefix + "/backup")
    backup_desk = backup["data"]["writing"]["desk"]
    require(content(backup_desk) == content(saved), "Complete backup omitted inspiration")
    require("mutationId" not in backup_desk, "Backup persisted a retry identity")
    restored_prefix = f'/novels/{works["restored"]}'
    api("POST", restored_prefix + "/backup", backup)
    restored_desk = api("GET", restored_prefix + "/writing/desk")
    require(content(restored_desk) == content(saved), "Full backup round trip changed the inbox")
    require(api("GET", restored_prefix + "/writing/chapters/" + first["uid"])["doc"] == first["doc"],
            "Restored chapter associations lost their manuscript")
    api("POST", restored_prefix + "/backup", backup)
    again = api("GET", restored_prefix + "/writing/desk")
    require(content(again) == content(saved) and again["version"] == restored_desk["version"] + 1,
            "Repeated restore duplicated ideas or failed to invalidate old editors")
    api("PUT", restored_prefix + "/writing/desk", mutation(restored_desk), expected=409)
    legacy = copy.deepcopy(backup)
    del legacy["data"]["writing"]["desk"]["ideas"]
    blocked = api("POST", prefix + "/backup", legacy, expected=400)
    require("旧备份不含灵感收件箱" in blocked.get("message", ""), "Missing legacy-backup explanation")
    invalid_backup = copy.deepcopy(backup)
    invalid_backup["data"]["writing"]["desk"]["ideas"][0]["category"] = "invalid"
    api("POST", prefix + "/backup", invalid_backup, expected=400)
    require(api("GET", prefix + "/writing/desk") == saved, "Rejected restore modified inspiration")
    require(api("GET", prefix + "/writing/chapters/" + first["uid"]) == merged, "Rejected restore modified prose")
    api("POST", f'/novels/{works["legacy"]}/backup', legacy)
    require(api("GET", f'/novels/{works["legacy"]}/writing/desk')["ideas"] == [], "Legacy backup failed on a fresh work")

    # Copy the existing encrypted loopback fixture only into this script's works.
    for target in (works["origin"], works["cloud"]):
        sql(f"UPDATE novels SET webdav_server_url=s.webdav_server_url, webdav_username=s.webdav_username, "
            f"webdav_password=s.webdav_password, webdav_auto_sync=false FROM "
            f"(SELECT * FROM novels WHERE id={source_id}) s WHERE novels.id={int(target)}")
    first_push = api("POST", prefix + "/writing/cloud/push", {})
    workspace = api("GET", prefix + "/writing")
    cloud_folder = run / "dav" / "novel-backups" / ("ink-" + workspace["uid"])

    def cloud_file(name):
        require(pathlib.Path(name).name == name, "Unexpected remote filename")
        path = cloud_folder / name
        require(path.is_file(), "WebDAV did not write the expected local file")
        return json.loads(path.read_text(encoding="utf-8"))

    require(content(cloud_file(first_push["file"])["bundle"]["data"]["writing"]["desk"]) == content(saved),
            "Actual WebDAV file omitted inspiration")
    require(workspace["syncedSequence"] == workspace["changeSequence"], "Upload did not acknowledge inbox changes")
    edited = mutation(saved)
    edited["ideas"][0]["body"] += "同步后的第二个想法。\n"
    changed = api("PUT", prefix + "/writing/desk", edited)
    pending = api("GET", prefix + "/writing")
    require(pending["changeSequence"] == workspace["changeSequence"] + 1 and
            pending["syncedSequence"] == workspace["syncedSequence"], "Idea-only edit was not pending synchronization")
    second_push = api("POST", prefix + "/writing/cloud/push", {})
    require(second_push["file"] != first_push["file"], "Idea-only edit failed to produce an immutable remote version")
    require(content(cloud_file(second_push["file"])["bundle"]["data"]["writing"]["desk"]) == content(changed),
            "Second WebDAV version contains stale ideas")
    require(content(cloud_file(first_push["file"])["bundle"]["data"]["writing"]["desk"]) == content(saved),
            "Uploading a new inbox version overwrote the old recovery baseline")
    cloud_prefix = f'/novels/{works["cloud"]}'
    local_chapter = chapter(works["cloud"], "第二设备本机旧稿", "这份原稿与灵感必须保留。")
    local = mutation(api("GET", cloud_prefix + "/writing/desk"))
    local["ideas"] = [{"uid": uid(), "title": "本机原灵感", "body": "云端恢复前应留在副本里。", "category": "other",
                       "chapterUids": [local_chapter["uid"]], "createdAt": moment, "updatedAt": moment}]
    local = api("PUT", cloud_prefix + "/writing/desk", local)
    pulled = api("POST", cloud_prefix + "/writing/cloud/pull", {"book": workspace["uid"], "file": second_push["file"]})
    require(content(api("GET", cloud_prefix + "/writing/desk")) == content(changed), "Second-device WebDAV restore changed ideas")
    require(api("GET", cloud_prefix + "/writing")["uid"] == workspace["uid"], "Second device did not adopt the cloud identity")
    copy_prefix = f'/novels/{int(pulled["copyNovelId"])}'
    require(content(api("GET", copy_prefix + "/writing/desk")) == content(local), "Cloud pull lost the second device's local ideas")
    require(api("GET", copy_prefix + "/writing/chapters/" + local_chapter["uid"])["doc"] == local_chapter["doc"],
            "Cloud pull lost the second device's original manuscript")

    result = {"status": "passed", "database": database, "works": works, "backupCopyNovelId": pulled["copyNovelId"],
              "bookUid": workspace["uid"], "ideaUid": idea["uid"], "sourceKey": source_key,
              "chapterUids": [first["uid"], second["uid"], trashed["uid"]],
              "cloudFiles": [first_push["file"], second_push["file"]],
              "checks": ["exact UTF-8 migration body", "multiple chapter associations", "sourceKey uniqueness",
                         "HTTP retry idempotency", "stale and reused mutation conflicts", "cross-work rejection",
                         "legacy client protection", "split expansion and retry", "merge deduplication",
                         "deleted chapter association retention", "complete backup and repeated restore",
                         "legacy and malformed backup protection", "legacy backup into fresh work",
                         "actual immutable WebDAV files", "idea-only sync sequence", "second-device restore and local recovery copy"]}
    result_path = run / "idea-inbox-result.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: C11 migration identity, HTTP conflicts/isolation, split/merge, complete backup, protected legacy restore, real WebDAV and recovery copy")
    print("Result:", result_path)
    print("Fixture works:", ", ".join(str(value) for value in works.values()), "— existing browser fixture and services remain unchanged")


if __name__ == "__main__":
    main()
