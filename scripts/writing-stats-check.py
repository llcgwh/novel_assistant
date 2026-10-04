#!/usr/bin/env python3
"""Verify C12 over real HTTP, disposable PostgreSQL, and loopback WebDAV.

    PGPORT=55432 python3 scripts/writing-stats-check.py /path/to/run.json

Requires integration-check.py --keep and honors IT_PORT, PG_BIN, PGUSER,
PGPASSWORD, and PGPORT. Creates independent test works. Does not start/stop
services or change existing QA works. Credentials are neither decrypted nor
printed. Add --capacity to exercise the 20000-receipt/36600-day retention limits.
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
DAY_KEYS = {"date", "goal", "revisionSaves", "finalTransitions", "completedChapterUids", "focusSeconds", "focusCompleted"}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def uid():
    return str(uuid.uuid4())


def document(text):
    return {"type": "doc", "content": [{"type": "paragraph", "attrs": {"id": uid()},
            "content": [{"type": "text", "text": text}]}]}


def blank_day(date, goal=None):
    return {"date": date, "goal": goal, "revisionSaves": 0, "finalTransitions": 0,
            "completedChapterUids": [], "focusSeconds": 0, "focusCompleted": 0}


def public_content(stats):
    return {"schemaVersion": stats["schemaVersion"], "days": stats["days"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("state", type=pathlib.Path, help="run.json produced by integration-check.py --keep")
    parser.add_argument("--capacity", action="store_true", help="also exercise full receipt/day capacity with synthetic backups")
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
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = f"http://127.0.0.1:{port}/api"

    def api(method, path, data=None, expected=200):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8") if data is not None else None
        request = urllib.request.Request(base + path, data=raw, method=method, headers={
            "Content-Type": "application/json", "Idempotency-Key": uid(),
        })
        try:
            with http.open(request, timeout=90) as response:
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

    # Choose a fixed offset whose current local clock is near noon. Requests can
    # then retain their exact original date without a midnight-sensitive test.
    now = datetime.datetime.now(datetime.timezone.utc)
    offset = now.hour * 60 + now.minute - 12 * 60
    local_today = (now - datetime.timedelta(minutes=offset)).date()
    today = local_today.isoformat()
    yesterday = (local_today - datetime.timedelta(days=1)).isoformat()
    older = (local_today - datetime.timedelta(days=2)).isoformat()
    future = (local_today + datetime.timedelta(days=1)).isoformat()
    date_context = {"date": today, "timezoneOffsetMinutes": offset}
    suffix = uid()[:8]
    works = {key: api("POST", "/novels", {"title": label + " " + suffix})["id"] for key, label in (
        ("origin", "创作统计 HTTP 验证"), ("restored", "统计完整备份恢复验证"), ("legacy", "统计旧包新作品验证"),
        ("cloud", "统计第二设备验证"), ("timezone", "统计时区符号验证"),
    )}
    prefix = f'/novels/{works["origin"]}'

    def stats(path=prefix):
        result = api("GET", path + "/writing/stats")
        require(set(result) == {"schemaVersion", "version", "days"} and result["schemaVersion"] == 1,
                "Public statistics leaked receipts or changed schema")
        require(all(set(row) == DAY_KEYS for row in result["days"]), "Daily statistics schema changed")
        return result

    def day(result, date):
        rows = [row for row in result["days"] if row["date"] == date]
        require(len(rows) == 1, "Expected one daily statistics row for " + date)
        return rows[0]

    def chapter(path, title, text, status="draft"):
        return api("POST", path + "/writing/chapters", {"uid": uid(), "title": title, "status": status, "doc": document(text)})

    def save(value, **changes):
        request = {**copy.deepcopy(value), **date_context, **changes, "mutationId": uid()}
        return api("PUT", prefix + "/writing/chapters/" + value["uid"], request), request

    def goal(number):
        workspace = api("GET", prefix + "/writing")
        return api("PUT", prefix + "/writing/preferences", {"structureVersion": workspace["structureVersion"],
                   "preferences": {**workspace["preferences"], "dailyGoal": number}, **date_context})

    def focus(receipt, path=prefix, expected=200):
        return api("POST", path + "/writing/stats/focus/" + receipt["uid"], receipt, expected=expected)

    initial = stats()
    require(initial["days"] == [], "New work has nonempty statistics")
    first = chapter(prefix, "甲章", "月光照着石桥。")
    second = chapter(prefix, "乙章", "远处传来钟声。", status="final")
    require(stats() == initial, "Creating or importing a final chapter counted as writing activity")
    require(api("GET", prefix + "/writing")["stats"] == initial, "Workspace statistics differ from GET stats")
    require(api("POST", prefix + "/writing/stats/day", {"date": yesterday, "timezoneOffsetMinutes": offset}) == initial,
            "Historical day request invented a goal snapshot")
    api("POST", prefix + "/writing/stats/day", {"date": future, "timezoneOffsetMinutes": offset}, expected=400)
    api("POST", prefix + "/writing/stats/day", {"date": today, "timezoneOffsetMinutes": 841}, expected=400)
    require(stats() == initial, "Rejected day request modified statistics")

    # There is intentionally no API to rewrite historic goals: seed a known
    # yesterday snapshot via the supported, fully validated restore boundary.
    seed = api("GET", prefix + "/backup")
    seed["data"]["writing"]["stats"]["days"] = [blank_day(yesterday, 1200)]
    api("POST", prefix + "/backup", seed)
    seeded = stats()
    require(seeded["days"] == [blank_day(yesterday, 1200)], "Restore fabricated activity or today's goal")
    first = api("GET", prefix + "/writing/chapters/" + first["uid"])
    second = api("GET", prefix + "/writing/chapters/" + second["uid"])
    captured = api("POST", prefix + "/writing/stats/day", date_context)
    require(day(captured, today) == blank_day(today, 2000), "Today did not snapshot its current target")
    sequence = api("GET", prefix + "/writing")["changeSequence"]
    require(api("POST", prefix + "/writing/stats/day", date_context) == captured, "Repeated day capture changed statistics")
    require(api("GET", prefix + "/writing")["changeSequence"] == sequence, "Repeated day capture changed sync sequence")
    goal(3000)
    require(day(stats(), yesterday)["goal"] == 1200 and day(stats(), today)["goal"] == 3000,
            "Changing today's goal rewrote a historic target")
    goal(0)
    require(day(stats(), today)["goal"] == 0, "Disabling today's goal was coerced to a default goal")
    goal(3000)

    # Same character count still changes prose; sessions/net counters stay separate.
    before = stats()
    changed_doc = copy.deepcopy(first["doc"])
    changed_doc["content"][0]["content"][0]["text"] = "星光照着石桥。"
    first, request = save(first, doc=changed_doc)
    require(first["wordCount"] == seed["data"]["writing"]["chapters"][0]["wordCount"], "Equal-length edit unexpectedly changed word count")
    after = stats()
    require(day(after, today)["revisionSaves"] == day(before, today)["revisionSaves"] + 1, "Zero-net prose edit was not counted")
    require(api("PUT", prefix + "/writing/chapters/" + first["uid"], request) == first, "Chapter retry changed saved result")
    require(stats() == after, "Exact chapter retry counted twice")
    stale = {**copy.deepcopy(request), "mutationId": uid()}
    api("PUT", prefix + "/writing/chapters/" + first["uid"], stale, expected=409)
    invalid = {**copy.deepcopy(first), **date_context, "status": "invalid", "mutationId": uid()}
    api("PUT", prefix + "/writing/chapters/" + first["uid"], invalid, expected=400)
    require(stats() == after, "Failed chapter save created revision activity")
    first, _ = save(first, title="只改标题", notes="创作卡元数据")
    marked = copy.deepcopy(first["doc"])
    marked["content"][0]["content"][0]["marks"] = [{"type": "bold"}]
    first, _ = save(first, doc=marked)
    first, _ = save(first, checkpoint=True)
    require(stats() == after, "Metadata, formatting, or manual checkpoint inflated revision activity")
    prior_words = first["wordCount"]
    shorter = copy.deepcopy(first["doc"])
    shorter["content"][0]["content"][0]["text"] = "石桥。"
    first, _ = save(first, doc=shorter)
    require(first["wordCount"] < prior_words and day(stats(), today)["revisionSaves"] == 2,
            "Pure deletion failed to count as one revision")
    require(api("GET", prefix + "/writing")["sessions"] == [], "Server revision activity fabricated net-growth sessions")

    # Final transition events and distinct completed chapters have different counts.
    first, final_request = save(first, status="final")
    require(day(stats(), today)["finalTransitions"] == 1 and day(stats(), today)["completedChapterUids"] == [first["uid"]],
            "First final transition was not recorded")
    unchanged = stats()
    api("PUT", prefix + "/writing/chapters/" + first["uid"], final_request)
    first, _ = save(first, title="定稿后只改标题")
    require(stats() == unchanged, "Final retry or saving an already-final chapter counted twice")
    first, _ = save(first, status="revision")
    first, _ = save(first, status="final")
    second, _ = save(second, status="revision")
    second, _ = save(second, status="final")
    final_day = day(stats(), today)
    require(final_day["finalTransitions"] == 3 and set(final_day["completedChapterUids"]) == {first["uid"], second["uid"]}
            and len(final_day["completedChapterUids"]) == 2 and final_day["revisionSaves"] == 2,
            "Final transitions or distinct chapter counts have the wrong semantics")
    historical_doc = copy.deepcopy(first["doc"])
    historical_doc["content"][0]["content"][0]["text"] = "古桥。"
    first, historical_request = save(first, doc=historical_doc, date=older)
    require(day(stats(), older)["goal"] is None and day(stats(), older)["revisionSaves"] == 1,
            "Delayed historical edit used today's goal or lost its activity date")
    historical_stats = stats()
    api("PUT", prefix + "/writing/chapters/" + first["uid"], historical_request)
    require(stats() == historical_stats, "Delayed historical retry counted twice")
    goal(4500)
    require(day(stats(), older)["goal"] is None and day(stats(), yesterday)["goal"] == 1200,
            "New daily goal backfilled unknown or known historical goals")

    cancelled = {"uid": uid(), "endedOn": today, "completed": False,
                 "secondsByDate": {yesterday: 40, today: 20}, "timezoneOffsetMinutes": offset}
    focus(cancelled)
    complete = {"uid": uid(), "endedOn": today, "completed": True,
                "secondsByDate": {yesterday: 100, today: 800}, "timezoneOffsetMinutes": offset}
    focus(complete)
    empty_cancel = {"uid": uid(), "endedOn": today, "completed": False, "secondsByDate": {}, "timezoneOffsetMinutes": offset}
    focus(empty_cancel)
    focused = stats()
    require(day(focused, yesterday)["focusSeconds"] == 140 and day(focused, yesterday)["focusCompleted"] == 0,
            "Cross-day focus seconds or completion date are wrong")
    require(day(focused, today)["focusSeconds"] == 820 and day(focused, today)["focusCompleted"] == 1,
            "Cancelled focus counted as completed or lost elapsed time")
    require(day(focused, older)["goal"] is None and day(focused, yesterday)["goal"] == 1200,
            "Focus upload changed historical goal snapshots")
    focus_sequence = api("GET", prefix + "/writing")["changeSequence"]
    for receipt in (cancelled, complete, empty_cancel):
        require(focus(receipt) == focused, "Focus retry changed public statistics")
    normalized_retry = {**complete, "secondsByDate": {today: 800, older: 0, yesterday: 100}, "timezoneOffsetMinutes": offset + 1}
    require(focus(normalized_retry) == focused, "Normalized receipt or retry-time timezone broke idempotency")
    focus({**complete, "completed": False}, expected=409)
    focus({**complete, "secondsByDate": {yesterday: 100, today: 801}}, expected=409)
    invalid_focus = [
        {**complete, "uid": uid(), "secondsByDate": {today: -1}},
        {**complete, "uid": uid(), "secondsByDate": {today: 1.5}},
        {**complete, "uid": uid(), "secondsByDate": {}},
        {**complete, "uid": uid(), "secondsByDate": {yesterday: 86400, today: 1}},
        {**complete, "uid": uid(), "endedOn": yesterday, "secondsByDate": {today: 1}},
        {**complete, "uid": uid(), "endedOn": future, "secondsByDate": {future: 1}},
    ]
    for receipt in invalid_focus:
        focus(receipt, expected=400)
    require(stats() == focused and api("GET", prefix + "/writing")["changeSequence"] == focus_sequence,
            "Rejected/repeated focus submission changed statistics or sync sequence")
    require(stats(f'/novels/{works["legacy"]}')["days"] == [], "Activity leaked into another work")

    # Explicitly check JavaScript getTimezoneOffset's sign at a UTC date boundary.
    boundary_offset = 840 if now.hour < 12 else -840
    boundary_date = (now - datetime.timedelta(minutes=boundary_offset)).date().isoformat()
    require(boundary_date != now.date().isoformat(), "Timezone fixture did not cross a UTC date boundary")
    timezone_stats = api("POST", f'/novels/{works["timezone"]}/writing/stats/day',
                         {"date": boundary_date, "timezoneOffsetMinutes": boundary_offset})
    require(timezone_stats["days"] == [blank_day(boundary_date, 2000)], "Timezone-offset sign or local date is wrong")

    backup = api("GET", prefix + "/backup")
    archived_stats = backup["data"]["writing"]["stats"]
    require(public_content(archived_stats) == public_content(focused) and len(archived_stats["focusReceipts"]) == 3,
            "Complete backup omitted daily statistics or focus retry receipts")
    require(all("timezoneOffsetMinutes" not in receipt for receipt in archived_stats["focusReceipts"]),
            "Backup persisted retry-time context as receipt identity")
    restored_prefix = f'/novels/{works["restored"]}'
    api("POST", restored_prefix + "/backup", backup)
    restored = stats(restored_prefix)
    require(public_content(restored) == public_content(focused), "Backup restore fabricated or changed statistics")
    for receipt in (cancelled, complete, empty_cancel):
        require(focus(receipt, restored_prefix) == restored, "Restore lost focus idempotency receipts")
    api("POST", restored_prefix + "/backup", backup)
    repeated = stats(restored_prefix)
    require(public_content(repeated) == public_content(restored) and repeated["version"] == restored["version"] + 1,
            "Repeated backup restore inflated activity or failed to advance local version")
    require(focus(complete, restored_prefix) == repeated, "Repeated restore made a receipt count again")
    require(api("GET", restored_prefix + "/writing/chapters/" + first["uid"])["doc"] == first["doc"],
            "Backup restore lost the manuscript")
    legacy = copy.deepcopy(backup)
    del legacy["data"]["writing"]["stats"]
    blocked = api("POST", prefix + "/backup", legacy, expected=400)
    require("不含创作统计与目标历史" in blocked.get("message", ""), "Missing legacy statistics restore explanation")
    inconsistent = copy.deepcopy(backup)
    next(row for row in inconsistent["data"]["writing"]["stats"]["days"] if row["date"] == today)["focusSeconds"] += 1
    api("POST", prefix + "/backup", inconsistent, expected=400)
    oversized = copy.deepcopy(backup)
    oversized["data"]["writing"]["stats"]["oversizedTestInput"] = "x" * (8 * 1024 * 1024)
    api("POST", prefix + "/backup", oversized, expected=400)
    require(stats() == focused and api("GET", prefix + "/writing/chapters/" + first["uid"]) == first,
            "Rejected legacy/inconsistent/oversized restore changed current work")
    api("POST", f'/novels/{works["legacy"]}/backup', legacy)
    require(stats(f'/novels/{works["legacy"]}')["days"] == [], "Legacy import fabricated revision, final, or goal activity")

    for target in (works["origin"], works["cloud"]):
        sql(f"UPDATE novels SET webdav_server_url=s.webdav_server_url, webdav_username=s.webdav_username, "
            f"webdav_password=s.webdav_password, webdav_auto_sync=false FROM "
            f"(SELECT * FROM novels WHERE id={source_id}) s WHERE novels.id={int(target)}")
    first_push = api("POST", prefix + "/writing/cloud/push", {})
    workspace = api("GET", prefix + "/writing")
    cloud_folder = run / "dav" / "novel-backups" / ("ink-" + workspace["uid"])

    def cloud_stats(name):
        require(pathlib.Path(name).name == name, "Unexpected remote filename")
        path = cloud_folder / name
        require(path.is_file(), "WebDAV did not write the expected local file")
        return json.loads(path.read_text(encoding="utf-8"))["bundle"]["data"]["writing"]["stats"]

    require(cloud_stats(first_push["file"]) == archived_stats, "Actual WebDAV file lost statistics or receipts")
    require(workspace["syncedSequence"] == workspace["changeSequence"], "Upload did not acknowledge statistics")
    extra = {"uid": uid(), "endedOn": today, "completed": False, "secondsByDate": {today: 30}, "timezoneOffsetMinutes": offset}
    latest = focus(extra)
    pending = api("GET", prefix + "/writing")
    require(pending["changeSequence"] == workspace["changeSequence"] + 1 and
            pending["syncedSequence"] == workspace["syncedSequence"], "Focus-only update was not pending synchronization")
    second_push = api("POST", prefix + "/writing/cloud/push", {})
    require(second_push["file"] != first_push["file"] and public_content(cloud_stats(second_push["file"])) == public_content(latest),
            "Focus-only edit did not produce an updated immutable WebDAV version")
    require(cloud_stats(first_push["file"]) == archived_stats, "New sync overwrote a statistics recovery baseline")
    cloud_prefix = f'/novels/{works["cloud"]}'
    local_chapter = chapter(cloud_prefix, "本机旧稿", "本机数据必须进入同步前副本。")
    local_receipt = {"uid": uid(), "endedOn": today, "completed": True, "secondsByDate": {today: 25}, "timezoneOffsetMinutes": offset}
    local_stats = focus(local_receipt, cloud_prefix)
    pulled = api("POST", cloud_prefix + "/writing/cloud/pull", {"book": workspace["uid"], "file": second_push["file"]})
    cloud_restored = stats(cloud_prefix)
    require(public_content(cloud_restored) == public_content(latest), "Second-device cloud restore changed statistics")
    require(focus(complete, cloud_prefix) == cloud_restored and focus(extra, cloud_prefix) == cloud_restored,
            "Cloud restore discarded receipts and counted retries again")
    copy_prefix = f'/novels/{int(pulled["copyNovelId"])}'
    local_copy = stats(copy_prefix)
    require(public_content(local_copy) == public_content(local_stats) and focus(local_receipt, copy_prefix) == local_copy,
            "Sync-before copy lost original focus history or its retry identity")
    require(api("GET", copy_prefix + "/writing/chapters/" + local_chapter["uid"])["doc"] == local_chapter["doc"],
            "Sync-before copy lost original prose")

    capacity_checks = []
    if args.capacity:
        capacity_id = api("POST", "/novels", {"title": "统计容量拒绝验证 " + suffix})["id"]
        works["capacity"] = capacity_id
        capacity_prefix = f"/novels/{capacity_id}"
        capacity_bundle = api("GET", capacity_prefix + "/backup")
        full = capacity_bundle["data"]["writing"]["stats"]
        full["focusReceipts"] = [{"uid": uid(), "endedOn": today, "completed": False, "secondsByDate": {}} for _ in range(20000)]
        api("POST", capacity_prefix + "/backup", capacity_bundle)
        full_stats = stats(capacity_prefix)
        focus({**empty_cancel, "uid": uid()}, capacity_prefix, expected=400)
        require(stats(capacity_prefix) == full_stats, "Receipt limit silently discarded earlier records")
        require(focus({**full["focusReceipts"][0], "timezoneOffsetMinutes": offset}, capacity_prefix) == full_stats,
                "An existing receipt could not retry at capacity")
        require(len(api("GET", capacity_prefix + "/backup")["data"]["writing"]["stats"]["focusReceipts"]) == 20000,
                "Receipt limit pruned historical receipts")
        capacity_checks.append("20000 receipts: reject new, retry old, retain all")
        full["focusReceipts"] = []
        full["days"] = [blank_day((local_today - datetime.timedelta(days=i + 1)).isoformat()) for i in range(36600)]
        api("POST", capacity_prefix + "/backup", capacity_bundle)
        full_stats = stats(capacity_prefix)
        api("POST", capacity_prefix + "/writing/stats/day", date_context, expected=400)
        require(stats(capacity_prefix) == full_stats and len(full_stats["days"]) == 36600,
                "Daily limit pruned history instead of rejecting a new date")
        capacity_checks.append("36600 days: reject new and retain all")

    result = {"status": "passed", "database": database, "works": works, "backupCopyNovelId": pulled["copyNovelId"],
              "dates": {"today": today, "yesterday": yesterday, "unknownGoalDay": older}, "timezoneOffsetMinutes": offset,
              "chapterUids": [first["uid"], second["uid"]], "focusUids": [cancelled["uid"], complete["uid"], empty_cancel["uid"], extra["uid"]],
              "cloudFiles": [first_push["file"], second_push["file"]], "capacityChecks": capacity_checks,
              "checks": ["public receipt privacy", "create/import/restore do not fabricate activity", "historical goal snapshots",
                         "zero and changed daily targets", "zero-net revision and pure deletion", "metadata/marks/checkpoint exclusion",
                         "failed and repeated saves", "final transitions versus distinct chapters", "delayed edit has unknown historical goal",
                         "cross-day cancelled/completed focus", "normalized retry and changed-terminal conflict", "invalid focus rejection",
                         "timezone sign and work isolation", "full backup with receipts", "repeat restore and focus retries",
                         "legacy/inconsistent/oversized backup protection", "legacy restore into a new work", "actual immutable WebDAV files",
                         "focus-only sync sequence", "second-device adoption and local history recovery"]}
    result_path = run / "writing-stats-result.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: C12 goal history, truthful activity, final transitions, cross-day focus, retries, complete backup, real WebDAV and recovery copy")
    if capacity_checks:
        print("PASS:", "; ".join(capacity_checks))
    print("Result:", result_path)
    print("Fixture works:", ", ".join(str(value) for value in works.values()), "— existing QA works and services remain unchanged")


if __name__ == "__main__":
    main()
