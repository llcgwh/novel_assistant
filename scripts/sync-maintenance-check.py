#!/usr/bin/env python3
"""C10 regression using integration-check.py --keep's disposable PostgreSQL/WebDAV.

Only this script's newly created works and immutable snapshot directory are
modified. The integration database marker and loopback DAV URL are mandatory.
Honors PG_BIN, PGUSER, PGPASSWORD, PGPORT and IT_PORT. Leaves a browser fixture.
"""
import argparse
import concurrent.futures
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('state', type=pathlib.Path)
    args = parser.parse_args()
    state_path = args.state.resolve(strict=True)
    state = json.loads(state_path.read_text())
    database = state.get('database', '')
    require(bool(re.fullmatch(r'novel_it_[a-zA-Z0-9]+', database)), 'Refusing non-disposable database')
    run = pathlib.Path(state['directory']).resolve(strict=True)
    require(state_path == run / 'run.json' and run.name.startswith('novel-integration-')
            and (run / 'dav').is_dir(), 'Invalid isolated integration workspace')
    port = int(os.environ.get('IT_PORT', '8081'))
    require(1 <= port <= 65535, 'Invalid HTTP port')
    base = f'http://127.0.0.1:{port}/api'
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def api(method, path, body=None, expected=(200,)):
        raw = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        request = urllib.request.Request(base + path, data=raw, method=method,
            headers={'Content-Type': 'application/json', 'Idempotency-Key': uid()})
        try:
            with http.open(request, timeout=100) as response:
                status, data = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, data = error.code, error.read()
        require(status in expected, f'{method} {path}: expected {expected}, received HTTP {status}')
        return json.loads(data) if data else None

    marker = 'Legacy integration ' + database
    novels = api('GET', '/novels')
    require(any(n['title'] == marker for n in novels), 'Wrong backend: integration marker missing')
    source = next(n for n in novels if n['title'] == '持续优化验证' and n.get('webdavServerUrl'))
    dav_url = urllib.parse.urlsplit(source['webdavServerUrl'])
    require(dav_url.scheme == 'http' and dav_url.hostname == '127.0.0.1', 'Refusing non-loopback DAV')
    props = dict(line.split('=', 1) for line in (ROOT / 'backend/src/main/resources/application.properties')
                 .read_text().splitlines() if '=' in line and not line.startswith('#'))
    environment = os.environ.copy()
    environment['PGPASSWORD'] = os.environ.get('PGPASSWORD', props['spring.datasource.password'])
    pg = pathlib.Path(os.environ.get('PG_BIN', '/Library/PostgreSQL/18/bin'))
    pg_user = os.environ.get('PGUSER', props['spring.datasource.username'])

    def sql(command):
        result = subprocess.run([str(pg / 'psql'), '-h', 'localhost', '-U', pg_user, '-d', database,
                                 '-v', 'ON_ERROR_STOP=1', '-Atc', command], env=environment,
                                capture_output=True, text=True)
        require(result.returncode == 0, 'Disposable SQL verification failed (credentials not printed)')
        return result.stdout.strip()

    require(sql(f"SELECT EXISTS (SELECT 1 FROM novels WHERE title='{marker}')") == 't', 'SQL marker missing')
    suffix = uid()[:8]
    works = {name: api('POST', '/novels', {'title': title + ' ' + suffix})['id'] for name, title in (
        ('origin', '同步维护验收'), ('other', '清理跨作品保护'),
        ('stale', '清理后旧设备'), ('failed', '失败记录验收'))}
    origin = works['origin']
    p = f'/novels/{origin}'
    for name in ('origin', 'other', 'stale'):
        sql('UPDATE novels SET webdav_server_url=s.webdav_server_url, webdav_username=s.webdav_username, '
            'webdav_password=s.webdav_password FROM '
            f'(SELECT * FROM novels WHERE id={int(source["id"])}) s WHERE novels.id={works[name]}')

    # Generate actual complete snapshots, then date only this fixture's files
    # into the past so retention is deterministic without changing system time.
    chapter = api('POST', p + '/writing/chapters', {'uid': uid(), 'title': '清理后仍可恢复的正文',
        'doc': {'type': 'doc', 'content': [{'type': 'paragraph', 'attrs': {'id': uid()},
                 'content': [{'type': 'text', 'text': '所有清理仅发生在隔离测试库。'}]}]}})
    book = api('GET', p + '/writing')['uid']
    folder = run / 'dav' / 'novel-backups' / ('ink-' + book)
    filenames = []
    for index in range(8):
        chapter = api('PUT', p + '/writing/chapters/' + chapter['uid'],
                      {**chapter, 'notes': f'历史版本 {index}', 'mutationId': uid()})
        filenames.append(api('POST', p + '/writing/cloud/push', {})['file'])
    renamed = []
    for index, old in enumerate(filenames):
        new = f'202001010000{index:02d}_' + old.split('_', 1)[1] if index < 7 else old
        if old != new:
            (folder / old).rename(folder / new)
        if index < 7:
            os.utime(folder / new, (1577836800 + index, 1577836800 + index))
        renamed.append(new)
    filenames = renamed
    untouched = folder / '作者保留的说明.txt'
    untouched.write_text('Unknown files must never be deleted by retention.\n')
    original_bytes = {name: hashlib.sha256((folder / name).read_bytes()).hexdigest() for name in filenames}
    original_heads = {row['revision'] for row in api('GET', p + '/writing/cloud/versions') if row['head']}
    require(len(original_heads) == 1, 'Fixture must have one head before cleanup')

    # A different local work represents a device still based on an old revision.
    stale_path = f'/novels/{works["stale"]}'
    api('POST', stale_path + '/writing/cloud/pull', {'book': book, 'file': filenames[2]})
    storage = api('GET', p + '/webdav/storage')
    require(storage['snapshotCount'] == 8 and storage['snapshotBytes'] > 0, 'Snapshot storage incorrect')
    require(storage['totalBytes'] >= storage['snapshotBytes'], 'Storage total omitted snapshots')
    policy = api('GET', p + '/webdav/retention')
    require(policy['automatic'] is False, 'Retention must never delete automatically')
    updated_policy = api('PUT', p + '/webdav/retention', {'keepLast': 2, 'keepDays': 1, 'version': policy['version']})
    require(updated_policy['version'] > policy['version'], 'Policy version did not advance')
    api('PUT', p + '/webdav/retention', {'keepLast': 3, 'keepDays': 1, 'version': policy['version']}, expected=(409,))

    def preview():
        return api('POST', p + '/webdav/cleanup/preview', {})

    def execute(plan, request_id=None, confirmed=True, path=p, expected=(200,)):
        return api('POST', path + '/webdav/cleanup/execute',
                   {'token': plan['token'], 'requestId': request_id or uid(), 'confirmed': confirmed}, expected)

    plan = preview()
    require(plan['candidates'] and plan['candidateBytes'] > 0, 'No reclaimable history in old fixture')
    candidates = {row['file'] for row in plan['candidates']}
    require(filenames[0] not in candidates and filenames[-1] not in candidates, 'Root/head must be protected')
    require(untouched.name not in candidates and all(name in filenames for name in candidates), 'Unknown file selected')
    require(all((folder / name).exists() for name in filenames), 'Preview deleted a file')
    execute(plan, confirmed=False, expected=(400,))
    execute(plan, path=f'/novels/{works["other"]}', expected=(400, 404, 409))
    api('DELETE', p + '/webdav/cleanup/' + plan['token'], expected=(200, 204))
    execute(plan, expected=(400, 409))
    require(all((folder / name).exists() for name in filenames), 'Cancel/invalid confirmation changed snapshots')

    expired = preview()
    require(bool(re.fullmatch(r'[a-f0-9-]{36}', expired['token'])), 'Invalid preview token')
    sql(f"UPDATE backup_cleanups SET expires_at=now()-interval '1 minute' WHERE token='{expired['token']}'")
    execute(expired, expected=(409,))
    changed = preview()
    changed_file = folder / 'changed-after-preview.txt'
    changed_file.write_text('A different device added this file after preview.\n')
    execute(changed, expected=(409,))
    changed_file.unlink()  # Only this script's throwaway marker, never a user backup.
    changed_policy = preview()
    api('PUT', p + '/webdav/retention', {'keepLast': 2, 'keepDays': 1, 'version': updated_policy['version']})
    execute(changed_policy, expected=(409,))

    # A valid-looking filename is not sufficient proof that a file belongs to
    # this recoverable snapshot graph. Refuse before deleting any candidate.
    corrupt_name = sorted(candidates)[0]
    corrupt_time = (folder / corrupt_name).stat().st_mtime
    intact = (folder / corrupt_name).read_bytes()
    broken = json.loads(intact)
    broken['bundle'] = {'format': 'invalid-test-format'}
    (folder / corrupt_name).write_text(json.dumps(broken))
    os.utime(folder / corrupt_name, (corrupt_time, corrupt_time))
    corrupt_preview = preview()
    corrupt_result = execute(corrupt_preview, expected=(200, 400, 409))
    require(not corrupt_result or corrupt_result.get('status') != 'SUCCEEDED', 'Invalid snapshot was accepted for cleanup')
    require(all((folder / name).exists() for name in filenames), 'Invalid candidate led to partial deletion')
    (folder / corrupt_name).write_bytes(intact)
    os.utime(folder / corrupt_name, (corrupt_time, corrupt_time))

    # Same request in two HTTP calls must perform at most one cleanup. Both may
    # return the completed result; a still-running receipt is also safe.
    plan = preview()
    request_id = uid()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _: execute(plan, request_id), range(2)))
    result = execute(plan, request_id)
    require(result['status'] == 'SUCCEEDED', 'Real DAV cleanup failed: ' + result.get('message', ''))
    require(all(row['status'] in ('SUCCEEDED', 'RUNNING') for row in responses), 'Unsafe duplicate confirmation result')
    require(set(result['deleted']) == {row['file'] for row in plan['candidates']}, 'Unexpected deletion scope')
    require(not result['uncertainFiles'], 'Unexpected uncertainty in healthy DAV cleanup')
    require(result['deletedBytes'] == plan['candidateBytes'], 'Freed-byte result differs from confirmed preview')
    require(all(not (folder / name).exists() for name in result['deleted']), 'Reported deletion not reflected on DAV')
    for name, digest in original_bytes.items():
        if name not in result['deleted']:
            require(hashlib.sha256((folder / name).read_bytes()).hexdigest() == digest, 'Retained snapshot changed')
    require(untouched.exists(), 'Unknown file was removed')
    require({row['revision'] for row in api('GET', p + '/writing/cloud/versions') if row['head']} == original_heads,
            'Cleanup changed cloud heads or revived a resolved branch')
    api('POST', stale_path + '/writing/cloud/push', {}, expected=(409,))
    old = api('GET', stale_path + '/writing/chapters/' + chapter['uid'])
    require(old['notes'] == '历史版本 2', 'Stale-device conflict overwrote local text')
    after = api('GET', p + '/webdav/storage')
    require(after['snapshotBytes'] == storage['snapshotBytes'] - result['deletedBytes'], 'Storage was not refreshed')
    api('DELETE', p + '/webdav/files', {'filename': 'not-a-real-file.json'}, expected=(400, 405, 409, 410))

    # Restore still keeps a complete recovery copy after history compaction.
    pulled = api('POST', p + '/writing/cloud/pull', {'book': book, 'file': filenames[-1]})
    require(api('GET', f'/novels/{pulled["copyNovelId"]}/writing/chapters/' + chapter['uid'])['notes'] == '历史版本 7',
            'Protected pre-restore copy lost the manuscript')
    api('GET', p + '/backup')
    api('POST', p + '/backup', {'format': 'deliberately-invalid-test-backup'}, expected=(400,))

    failed_path = f'/novels/{works["failed"]}'
    secret = 'test-only-' + uid()
    api('POST', failed_path + '/webdav/config', {'serverUrl': source['webdavServerUrl'], 'username': 'writer', 'password': secret})
    api('POST', failed_path + '/writing/cloud/push', {}, expected=(400, 401, 403, 500, 502))
    failures = api('GET', failed_path + '/webdav/operations')['items']
    require(any(row['status'] == 'FAILED' and row['httpStatus'] == 401 for row in failures), 'Missing traceable DAV HTTP failure')
    require(secret not in json.dumps(failures), 'Operation history leaked credentials')
    page = api('GET', p + '/webdav/operations?limit=2')
    require(len(page['items']) == 2 and page['nextBefore'], 'History pagination omitted older operations')
    older = api('GET', p + '/webdav/operations?limit=100&before=' + str(page['nextBefore']))
    ids = {row['id'] for row in page['items']}
    require(not ids.intersection(row['id'] for row in older['items']), 'History pagination duplicated rows')
    history = api('GET', p + '/webdav/operations?limit=100')['items']
    require(any(row['status'] == 'FAILED' for row in history), 'Restore rollback lost its failure record')
    require(any(row['id'] == result['operationId'] and row['status'] == 'SUCCEEDED' for row in history), 'Cleanup receipt missing')
    require(int(sql(f'SELECT count(*) FROM backup_operations WHERE novel_id={origin}')) >= len(history), 'History not persisted')
    output = {'novelId': origin, 'otherNovelId': works['other'], 'staleNovelId': works['stale'],
              'failedNovelId': works['failed'], 'bookUid': book, 'chapterUid': chapter['uid'],
              'cleanupOperationId': result['operationId'], 'deleted': result['deleted'],
              'deletedBytes': result['deletedBytes'], 'remainingHeadRevisions': sorted(original_heads),
              'historyIds': [row['id'] for row in history]}
    (run / 'sync-maintenance-result.json').write_text(json.dumps(output, ensure_ascii=False, indent=2))
    print('PASS: durable history/failures, storage, policy CAS, safe preview/cancel/expiry/staleness, '
          'real DAV locks/conditional deletion, duplicate confirmation, graph preservation, stale-device conflict, recovery copy')
    print('Browser fixture and evidence:', run / 'sync-maintenance-result.json')


if __name__ == '__main__':
    main()
