#!/usr/bin/env python3
"""C08 real HTTP/PostgreSQL/WebDAV regression on integration-check.py --keep.

Creates fictional works and a fictional source library only in the marked
novel_it_* database. Never uses a non-loopback WebDAV server or prints secrets.
Honors IT_PORT, PG_BIN, PGPORT, PGUSER and PGPASSWORD. Leaves browser fixtures.
"""
import argparse
import base64
import concurrent.futures
import copy
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
FIELDS = {
    'calendar': ['epoch', 'units', 'dateFormat', 'rules'],
    'location': ['geography', 'environment', 'culture'],
    'race': ['traits', 'origins', 'culture'],
    'organization': ['purpose', 'structure', 'rules'],
    'character': ['role', 'personality', 'appearance', 'background'],
}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def uid():
    return str(uuid.uuid4())


def normalized(state):
    return {k: v for k, v in state.items() if k not in ('version', 'epoch')}


def revision_hash(revision):
    value = {k: v for k, v in revision.items() if k != 'hash'}
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))
    # Jackson's UTF-8 byte generator emits supplementary characters as uppercase
    # UTF-16 surrogate escapes and uses uppercase hex for escaped controls. Skip
    # escaped backslashes so a literal author-entered "\\uabcd" stays unchanged.
    pieces, index = [], 0
    while index < len(encoded):
        char = encoded[index]
        if char == '\\':
            if encoded[index + 1] == 'u':
                pieces.append('\\u' + encoded[index + 2:index + 6].upper())
                index += 6
            else:
                pieces.append(encoded[index:index + 2])
                index += 2
        elif ord(char) > 65535:
            value = ord(char) - 65536
            pieces.append('\\u%04X\\u%04X' % (0xD800 + (value >> 10), 0xDC00 + (value & 1023)))
            index += 1
        else:
            pieces.append(char)
            index += 1
    return hashlib.sha256(''.join(pieces).encode('utf-8')).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('state', type=pathlib.Path)
    args = parser.parse_args()
    state_path = args.state.resolve(strict=True)
    state = json.loads(state_path.read_text(encoding='utf-8'))
    database = state.get('database', '')
    require(bool(re.fullmatch(r'novel_it_[a-zA-Z0-9]+', database)), 'Refusing non-disposable database')
    run = pathlib.Path(state['directory']).resolve(strict=True)
    require(state_path == run / 'run.json' and run.name.startswith('novel-integration-')
            and (run / 'dav').is_dir(), 'Invalid isolated integration workspace')
    port = int(os.environ.get('IT_PORT', '8081'))
    require(1 <= port <= 65535, 'Invalid loopback port')
    base = f'http://127.0.0.1:{port}/api'
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def api(method, path, body=None, expected=(200,), raw=None, content_type='application/json'):
        if isinstance(expected, int):
            expected = (expected,)
        data = raw if raw is not None else json.dumps(body, ensure_ascii=False).encode('utf-8') if body is not None else None
        request = urllib.request.Request(base + path, data=data, method=method,
            headers={'Content-Type': content_type, 'Idempotency-Key': uid()})
        try:
            with http.open(request, timeout=90) as response:
                status, received = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, received = error.code, error.read()
        result = json.loads(received) if received else None
        detail = result.get('message', '')[:200] if isinstance(result, dict) else ''
        require(status in expected, f'{method} {path}: expected {expected}, received {status}: {detail}')
        return result

    marker = 'Legacy integration ' + database
    novels = api('GET', '/novels')
    require(any(n['title'] == marker for n in novels), 'Wrong backend: integration marker missing')
    dav_source = next(n for n in novels if n['title'] == '持续优化验证' and n.get('webdavServerUrl'))
    dav_url = urllib.parse.urlsplit(dav_source['webdavServerUrl'])
    require(dav_url.scheme == 'http' and dav_url.hostname == '127.0.0.1', 'Refusing non-loopback DAV')
    props = dict(line.split('=', 1) for line in (ROOT / 'backend/src/main/resources/application.properties')
                 .read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
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
        ('origin', '系列母本验收'), ('other', '另一星球独立作品'), ('restored', '系列设定恢复验收'),
        ('legacy', '旧版作品包验收'), ('cloud', '系列设定第二设备'), ('worldOnly', '只有世界设定验收'),
        ('firstExport', '首次备份稳定初始化'), ('failedExport', '失败备份初始化回滚'))}
    a, b = works['origin'], works['other']

    def series(novel):
        return api('GET', f'/novels/{novel}/series')

    def request_for(document, **values):
        return {'mutationId': uid(), 'epoch': document['epoch'], 'expectedVersion': document['version'], **values}

    def mutate(novel, method, path, values, current=None):
        document = current if current is not None else series(novel)
        request = request_for(document, **values)
        response = api(method, f'/novels/{novel}/series' + path, request)
        return response['state'], request

    def find_copy(document, identity):
        return next(c for c in document['copies'] if c['uid'] == identity)

    def template(identity):
        return api('GET', '/series/templates/' + identity)

    def publish(identity, payload, current=None):
        current = current or template(identity)['template']
        body = {'mutationId': uid(), 'expectedLockVersion': current['lockVersion'],
                'expectedHeadRevisionUid': current['headRevisionUid'], 'payload': payload,
                'seriesName': '星海系列 ' + suffix, 'authorStatus': 'confirmed', 'changeNote': '作者明确发布的新版本'}
        return api('POST', '/series/templates/' + identity + '/revisions', body), body

    first_backup = api('GET', f'/novels/{works["firstExport"]}/backup')
    second_backup = api('GET', f'/novels/{works["firstExport"]}/backup')
    require(first_backup['data']['series']['document'] == second_backup['data']['series']['document'] and
            first_backup['data']['writing']['uid'] == second_backup['data']['writing']['uid'],
            'Export of an uninitialized work invented new source identities each time')
    require(normalized(series(works['firstExport'])) == normalized(first_backup['data']['series']['document']),
            'First export default world differs from persisted state')

    # Initialization is stable and does not invent copies or write activity.
    initial = series(a)
    require(initial == series(a) and initial['copies'] == [] and initial['version'] == 0, 'Unstable empty series document')
    require(len(initial['worlds']) == 1, 'Missing explicit default world')
    other_before = series(b)
    current = initial
    templates, revisions, copies = {}, {}, {}
    create_requests = {}
    for kind, extras in FIELDS.items():
        identity = uid()
        payload = {field: f'{kind} 的 {field}\n第二行保留。🌙' for field in ['description', 'notes', *extras]}
        payload['name'] = f'星海{kind}模板 {suffix}'
        body = {'mutationId': uid(), 'templateUid': identity, 'kind': kind, 'seriesName': '星海系列 ' + suffix,
                'payload': payload, 'authorStatus': 'confirmed', 'changeNote': '首次作者设定'}
        source = api('POST', '/series/templates', body)
        templates[kind], revisions[kind], create_requests[kind] = identity, source['revision'], body
        local_uid = uid()
        current, copied_request = mutate(a, 'POST', '/copies', {'copyUid': local_uid, 'templateUid': identity,
            'revisionUid': source['revision']['uid'], 'universeUid': initial['worlds'][0]['uid'], 'planet': '苍梧星'}, current)
        copies[kind] = local_uid
        local = find_copy(current, local_uid)
        require(local['content'] == payload and local['authorStatus'] == 'draft', 'Incomplete payload copy or inherited local confirmation')
        require(local['origin']['revisionUid'] == source['revision']['uid'], 'Missing pinned source provenance')
    require(series(b) == other_before, 'Global creation or another work copy altered unrelated work')
    current = series(a)
    require(len(current['copies']) == 5, 'Five template kinds did not persist')
    require(api('GET', f'/novels/{a}/characters') == [], 'Character template silently created a character record')
    require(sum(c['wordCount'] for c in api('GET', f'/novels/{a}/writing')['chapters']) == 0,
            'Setting copies affected manuscript word count')

    character_uid, source_uid = copies['character'], templates['character']
    world_uid = uid()
    world_request = request_for(current, worldUid=world_uid, name='平行宇宙乙', description='独立作者设定')
    before_sequence = api('GET', f'/novels/{a}/writing')['changeSequence']
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        twin_results = list(pool.map(lambda _: api('POST', f'/novels/{a}/series/worlds', world_request), range(2)))
    require(sum(r['replayed'] for r in twin_results) == 1, 'Concurrent identical request did not replay exactly once')
    current = series(a)
    require(current['version'] == world_request['expectedVersion'] + 1 and
            api('GET', f'/novels/{a}/writing')['changeSequence'] == before_sequence + 1,
            'Concurrent exact retry duplicated version or sync activity')
    parallel_uid = uid()
    current, _ = mutate(a, 'POST', '/copies/' + character_uid + '/duplicate',
        {'copyUid': parallel_uid, 'universeUid': world_uid, 'planet': '青霄星'}, current)
    parallel_before = copy.deepcopy(find_copy(current, parallel_uid))
    other_uid = uid()
    other, _ = mutate(b, 'POST', '/copies', {'copyUid': other_uid, 'templateUid': source_uid,
        'revisionUid': revisions['character']['uid'], 'universeUid': other_before['worlds'][0]['uid'], 'planet': '旧港星'}, other_before)
    local = find_copy(current, character_uid)
    local_content = {**local['content'], 'name': '本作守塔人', 'description': '本作先改的身世。'}
    current, local_edit = mutate(a, 'PUT', '/copies/' + character_uid,
        {'content': local_content, 'authorStatus': 'confirmed', 'planet': '苍梧星'}, current)
    a_sequence = api('GET', f'/novels/{a}/writing')['changeSequence']
    b_sequence = api('GET', f'/novels/{b}/writing')['changeSequence']
    incoming = {**revisions['character']['payload'], 'description': '母本更新的身世。', 'notes': '母本第二版新注记。'}
    v2, _ = publish(source_uid, incoming)
    require(api('GET', f'/novels/{a}/writing')['changeSequence'] == a_sequence and
            api('GET', f'/novels/{b}/writing')['changeSequence'] == b_sequence, 'Global publication dirtied unrelated works')
    require(series(a) == current and series(b) == other, 'Global publication rewrote local copies')
    compare_path = f'/novels/{a}/series/copies/{character_uid}/compare'
    comparison = api('POST', compare_path, {'revisionUid': v2['revision']['uid']})
    fields = {x['key']: x for x in comparison['fields']}
    require(fields['description']['conflict'] and fields['description']['localChanged'] and fields['description']['sourceChanged'],
            'Three-way conflicting edit was not explained')
    require(fields['notes']['sourceChanged'] and not fields['notes']['localChanged'] and not fields['notes']['conflict'],
            'Source-only change mislabeled')
    require(fields['name']['localChanged'] and not fields['name']['sourceChanged'], 'Local name override not preserved in comparison')
    v3, _ = publish(source_uid, {**incoming, 'background': '第三版才添加的背景。'})
    frozen = {k: comparison[k] for k in ('epoch', 'expectedVersion', 'copyHash', 'baselineRevisionUid')}
    adopt_body = {**frozen, 'mutationId': uid(), 'revisionUid': v2['revision']['uid'], 'selectedFields': ['description']}
    api('POST', f'/novels/{a}/series/copies/{character_uid}/adopt',
        {**adopt_body, 'mutationId': uid(), 'selectedFields': []}, expected=400)
    require(series(a) == current, 'Empty adoption silently advanced review or history')
    adopted = api('POST', f'/novels/{a}/series/copies/{character_uid}/adopt', adopt_body)
    current = adopted['state']
    selected = find_copy(current, character_uid)
    require(selected['content']['name'] == '本作守塔人' and selected['content']['description'] == incoming['description'] and
            selected['content']['notes'] == local_content['notes'], 'Partial adoption overwrote unselected local fields')
    require(selected['baselineRevisionUid'] == v2['revision']['uid'] and selected['authorStatus'] == 'draft',
            'Pinned v2 changed to v3 or stale author confirmation survived changed content')
    require(find_copy(current, parallel_uid) == parallel_before and series(b) == other, 'Adoption leaked across universes or works')
    comparison3 = api('POST', compare_path, {'revisionUid': v3['revision']['uid']})
    notes = next(x for x in comparison3['fields'] if x['key'] == 'notes')
    require(not notes['sourceChanged'] and notes['localChanged'], 'Declined v2 field did not become an explicit local override')

    # Exact replay remains safe after unrelated edits; reusing its identity is rejected.
    current, _ = mutate(a, 'PUT', '/worlds/' + world_uid, {'name': '平行宇宙乙（独立）', 'description': '保留全部副本'}, current)
    sequence = api('GET', f'/novels/{a}/writing')['changeSequence']
    replay = api('POST', f'/novels/{a}/series/copies/{character_uid}/adopt', adopt_body)
    require(replay['replayed'] and replay['state'] == current and replay['resultVersion'] == adopted['resultVersion'],
            'Lost-response retry did not return current state and original result version')
    require(api('GET', f'/novels/{a}/writing')['changeSequence'] == sequence, 'Replay created another sync activity')
    api('POST', f'/novels/{a}/series/copies/{character_uid}/adopt', {**adopt_body, 'selectedFields': ['notes']}, expected=409)
    api('PUT', f'/novels/{a}/series/copies/{character_uid}', {**local_edit, 'mutationId': uid()}, expected=409)
    api('PUT', f'/novels/{b}/series/copies/{character_uid}', request_for(other,
        content=local_content, authorStatus='draft', planet=''), expected=404)

    # Two concurrent writes based on one state cannot both apply.
    current, _ = mutate(a, 'PUT', '/copies/' + character_uid,
        {'content': selected['content'], 'authorStatus': 'confirmed', 'planet': selected['planet']}, current)
    comparison3 = api('POST', compare_path, {'revisionUid': v3['revision']['uid']})
    review = {k: comparison3[k] for k in ('epoch', 'expectedVersion', 'copyHash', 'baselineRevisionUid')}
    review.update(revisionUid=v3['revision']['uid'])
    bodies = [{**review, 'mutationId': uid()} for _ in range(2)]
    def review_once(body):
        return api('POST', f'/novels/{a}/series/copies/{character_uid}/review', body, expected=(200, 409))
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(review_once, bodies))
    require(sum('state' in r for r in results) == 1, 'Concurrent review did not reject one stale writer')
    reviewed = series(a)
    reviewed_copy = find_copy(reviewed, character_uid)
    require(reviewed_copy['content'] == selected['content'] and reviewed_copy['baselineRevisionUid'] == v3['revision']['uid']
            and reviewed_copy['authorStatus'] == 'confirmed',
            'Review-only changed content or failed to advance full baseline')
    history = next(h for h in reviewed_copy['history'] if h['action'] == 'adopt')
    head_before = template(source_uid)['template']
    current, _ = mutate(a, 'POST', '/copies/' + character_uid + '/restore', {'historyUid': history['uid']}, reviewed)
    restored_copy = find_copy(current, character_uid)
    require(restored_copy['content'] == history['before']['content'] and
            len(restored_copy['history']) == len(reviewed_copy['history']) + 1, 'Local history restore lost present state or old history')
    require(template(source_uid)['template'] == head_before and series(b) == other, 'Local history restore changed global head or another work')
    api('POST', f'/novels/{a}/series/worlds/{world_uid}/remove', request_for(current), expected=(400, 409))

    # A global compare-and-swap publication race has one winner.
    latest = template(templates['organization'])['template']
    bodies = [{'mutationId': uid(), 'expectedLockVersion': latest['lockVersion'], 'expectedHeadRevisionUid': latest['headRevisionUid'],
               'payload': {**revisions['organization']['payload'], 'rules': f'并发作者规则 {i}'},
               'seriesName': '星海系列 ' + suffix, 'authorStatus': 'draft', 'changeNote': '并发保护验证'} for i in range(2)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda body: api('POST', '/series/templates/' + templates['organization'] + '/revisions',
                                                body, expected=(200, 409)), bodies))
    require(sum('revision' in r for r in results) == 1, 'Concurrent source publication had multiple winners')
    replay = api('POST', '/series/templates', create_requests['organization'])
    require(replay['replayed'] and replay['revision']['uid'] == revisions['organization']['uid'], 'Source create retry changed original version')

    # Archive is reversible and never removes existing author copies.
    source = template(source_uid)['template']
    archived = api('POST', '/series/templates/' + source_uid + '/archive',
        {'mutationId': uid(), 'expectedLockVersion': source['lockVersion'], 'archived': True})
    api('POST', f'/novels/{a}/series/copies', request_for(current, copyUid=uid(), templateUid=source_uid,
        revisionUid=v3['revision']['uid'], universeUid=initial['worlds'][0]['uid'], planet=''), expected=(400, 409))
    api('POST', '/series/templates/' + source_uid + '/archive',
        {'mutationId': uid(), 'expectedLockVersion': archived['template']['lockVersion'], 'archived': False})
    require(series(a) == current, 'Source archival mutated novel copies')

    # Complete standalone source export includes versions never adopted by a work.
    unused, _ = publish(source_uid, {**v3['revision']['payload'], 'notes': '尚未被任何作品采用的第四版。'})
    pack = api('POST', '/series/export', {'templateUids': list(templates.values())})
    require(any(r['uid'] == unused['revision']['uid'] for r in pack['revisions']), 'Source export omitted unused version')
    preview = api('POST', '/series/import/preview', {'package': pack})
    import_body = {'mutationId': uid(), 'planToken': preview['planToken'], 'package': pack}
    imported = api('POST', '/series/import', import_body)
    require(imported['createdTemplates'] == 0 and imported['addedRevisions'] == 0, 'Same source package duplicated records')
    require(api('POST', '/series/import', import_body)['replayed'], 'Import exact retry checked changed plan before receipt')
    tampered = copy.deepcopy(pack)
    tampered['revisions'][0]['payload']['description'] += '篡改且未更新校验。'
    api('POST', '/series/import/preview', {'package': tampered}, expected=(400, 409))
    broken = copy.deepcopy(pack)
    broken['revisions'] = [r for r in broken['revisions'] if r['uid'] != revisions['character']['uid']]
    api('POST', '/series/import/preview', {'package': broken}, expected=(400, 409))
    require(revision_hash(revisions['character']) == revisions['character']['hash'], 'Portable revision hash differs from server')
    # A syntactically valid altered immutable identity still cannot replace an old revision.
    for key, value in (('createdAt', '2020-01-01T00:00:00Z'), ('changeNote', '同一身份不得改写的说明')):
        collision = copy.deepcopy(pack)
        collision['revisions'][0][key] = value
        collision['revisions'][0]['hash'] = revision_hash(collision['revisions'][0])
        api('POST', '/series/import/preview', {'package': collision}, expected=409)
    branch = {**copy.deepcopy(revisions['character']), 'uid': uid(), 'number': 2,
              'parentRevisionUid': revisions['character']['uid'], 'changeNote': '另一设备的独立第二版'}
    branch['payload']['notes'] = '同为第二版，依靠 UUID 保持独立分支。'
    branch['hash'] = revision_hash(branch)
    foreign_template = uid()
    imported_revision = {**copy.deepcopy(revisions['race']), 'uid': uid(), 'templateUid': foreign_template,
                         'changeNote': '仅通过完整母本包带来的全新种族模板'}
    imported_revision['hash'] = revision_hash(imported_revision)
    branches = {**pack, 'templates': [
        {'uid': source_uid, 'kind': 'character', 'headRevisionUid': branch['uid'], 'archived': False},
        {'uid': foreign_template, 'kind': 'race', 'headRevisionUid': imported_revision['uid'], 'archived': False}],
        'revisions': [revisions['character'], branch, imported_revision]}
    before_import_head = template(source_uid)['template']['headRevisionUid']
    branch_preview = api('POST', '/series/import/preview', {'package': branches})
    branch_request = {'mutationId': uid(), 'planToken': branch_preview['planToken'], 'package': branches}
    source_metadata = template(source_uid)['template']
    toggled = api('POST', '/series/templates/' + source_uid + '/archive',
        {'mutationId': uid(), 'expectedLockVersion': source_metadata['lockVersion'], 'archived': True})
    api('POST', '/series/import', branch_request, expected=409)
    require(not any(r['uid'] == branch['uid'] for r in template(source_uid)['revisions']), 'Stale import partially merged a version')
    api('GET', '/series/templates/' + foreign_template, expected=404)
    api('POST', '/series/templates/' + source_uid + '/archive',
        {'mutationId': uid(), 'expectedLockVersion': toggled['template']['lockVersion'], 'archived': False})
    branch_preview = api('POST', '/series/import/preview', {'package': branches})
    branch_request = {'mutationId': uid(), 'planToken': branch_preview['planToken'], 'package': branches}
    branch_result = api('POST', '/series/import', branch_request)
    require(branch_result['createdTemplates'] == 1 and branch_result['addedRevisions'] == 2,
            'New source/parallel version import did not merge completely')
    require(template(source_uid)['template']['headRevisionUid'] == before_import_head,
            'Import silently switched an existing shared source head')
    versions = template(source_uid)['revisions']
    require(len([r for r in versions if r['number'] == 2]) == 2, 'Parallel display-number versions were collapsed')
    require(api('POST', '/series/import', branch_request)['replayed'], 'Changed import preview broke exact successful retry')
    again_preview = api('POST', '/series/import/preview', {'package': branches})
    require(again_preview['createTemplates'] == 0 and again_preview['addRevisions'] == 0,
            'Importing identical package a second time would duplicate versions')
    require(any(x['uid'] == foreign_template for x in api('POST', '/series/export', {})['templates']),
            'Whole-library export omitted a template unused by every novel')

    # Attach a real image and manuscript before testing destructive restore guards.
    paragraph = {'type': 'doc', 'content': [{'type': 'paragraph', 'attrs': {'id': uid()},
                 'content': [{'type': 'text', 'text': '母本恢复不能删掉这份独立稿件。'}]}]}
    chapter = api('POST', f'/novels/{a}/writing/chapters', {'uid': uid(), 'title': '系列作品正文', 'doc': paragraph})
    png = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7OQAAAAASUVORK5CYII=')
    boundary = 'SeriesFixture' + uuid.uuid4().hex
    multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="pixel.png"\r\n'
                 'Content-Type: image/png\r\n\r\n').encode() + png + f'\r\n--{boundary}--\r\n'.encode()
    image_record = api('POST', f'/novels/{a}/images', raw=multipart, content_type='multipart/form-data; boundary=' + boundary)
    image_path = pathlib.Path(image_record['filePath'])
    require(image_path.resolve().is_relative_to((run / 'uploads').resolve()), 'Image path left isolated uploads')
    # A failed first export must not leave half-initialized author state behind.
    failed_id = works['failedExport']
    require(sql(f'SELECT count(*) FROM writing_books WHERE novel_id={failed_id}') == '0', 'Failed-export fixture was already initialized')
    missing_image = api('POST', f'/novels/{failed_id}/images', raw=multipart, content_type='multipart/form-data; boundary=' + boundary)
    missing_path = pathlib.Path(missing_image['filePath'])
    require(missing_path.resolve().is_relative_to((run / 'uploads' / str(failed_id)).resolve()), 'Missing-image fixture escaped its work')
    # The existing image HTTP interceptor initializes a book to mark uploads
    # dirty. The failed export must preserve that exact row, with series NULL.
    original_book = sql(f'SELECT row_to_json(b) FROM writing_books b WHERE novel_id={failed_id}')
    require(json.loads(original_book)['series_data'] is None, 'Image upload unexpectedly initialized series state')
    missing_path.unlink()
    try:
        api('GET', f'/novels/{failed_id}/backup', expected=400)
        require(sql(f'SELECT row_to_json(b) FROM writing_books b WHERE novel_id={failed_id}') == original_book,
                'Failed backup committed partially initialized book/source state')
    finally:
        missing_path.write_bytes(png)
    backup = api('GET', f'/novels/{a}/backup')
    require(backup['data']['schemaVersion'] == 4 and 'series' in backup['data'], 'Full backup did not advance to schema 4')
    require('epoch' not in backup['data']['series']['document'], 'Backup imported actionable browser epoch')
    ids = {r['uid'] for r in backup['data']['series']['library']['revisions']}
    require({revisions['character']['uid'], v2['revision']['uid'], v3['revision']['uid']} <= ids,
            'Source closure omitted origins, review history or ancestors')
    require(unused['revision']['uid'] not in ids, 'Novel backup silently included unrelated unused shared source')
    before = series(a)
    old = copy.deepcopy(backup)
    old['data']['schemaVersion'] = 3
    del old['data']['series']
    api('POST', f'/novels/{a}/backup', old, expected=409)
    missing = copy.deepcopy(backup)
    del missing['data']['series']
    api('POST', f'/novels/{a}/backup', missing, expected=400)
    invalid_world = copy.deepcopy(backup)
    invalid_world['data']['series']['document']['copies'][0]['universeUid'] = uid()
    api('POST', f'/novels/{a}/backup', invalid_world, expected=400)
    require(series(a) == before and image_path.read_bytes() == png and
            api('GET', f'/novels/{a}/writing/chapters/' + chapter['uid']) == chapter,
            'Rejected source restore changed document, image or manuscript')
    world_only = series(works['worldOnly'])
    world_only, _ = mutate(works['worldOnly'], 'PUT', '/worlds/' + world_only['worlds'][0]['uid'],
        {'name': '只修改世界名称也必须受保护', 'description': ''}, world_only)
    api('POST', f'/novels/{works["worldOnly"]}/backup', old, expected=409)
    require(series(works['worldOnly']) == world_only, 'Old backup erased world-only author edits')
    api('POST', f'/novels/{works["legacy"]}/backup', old)
    require(series(works['legacy'])['copies'] == [], 'Old backup failed to restore into a fresh work')

    # Restore-to-new and restore-over-existing preserve local history and source identity.
    global_before = template(source_uid)
    api('POST', f'/novels/{works["restored"]}/backup', backup)
    fresh = series(works['restored'])
    require(normalized(fresh) == normalized(before) and fresh['epoch'] != before['epoch'], 'Cross-work backup round trip lost source history')
    changed = find_copy(before, character_uid)
    after_edit, obsolete_request = mutate(a, 'PUT', '/copies/' + character_uid,
        {'content': {**changed['content'], 'notes': '恢复前本作最后的独立笔记。'}, 'authorStatus': 'draft', 'planet': changed['planet']}, before)
    restored = api('POST', f'/novels/{a}/backup', backup)
    current = series(a)
    require(current['epoch'] != after_edit['epoch'] and current['version'] == max(before['version'], after_edit['version']) + 1,
            'Restore did not invalidate pre-restore editors')
    require(normalized(current) == normalized(before), 'Restore changed pinned copies or history')
    require(normalized(series(restored['copyNovelId'])) == normalized(after_edit), 'Recovery copy omitted last local series edits')
    api('PUT', f'/novels/{a}/series/copies/{character_uid}', obsolete_request, expected=409)
    require(template(source_uid) == global_before and series(b) == other, 'Novel restore changed shared head or another work')

    # C08-only changes produce actual immutable DAV snapshots and recoverable copies.
    for novel in (a, works['cloud']):
        sql('UPDATE novels SET webdav_server_url=s.webdav_server_url, webdav_username=s.webdav_username, '
            'webdav_password=s.webdav_password FROM '
            f'(SELECT * FROM novels WHERE id={int(dav_source["id"])}) s WHERE novels.id={novel}')
    workspace = api('GET', f'/novels/{a}/writing')
    first = api('POST', f'/novels/{a}/writing/cloud/push', {})
    folder = run / 'dav' / 'novel-backups' / ('ink-' + workspace['uid'])
    cloud_bytes = (folder / first['file']).read_bytes()
    cloud_data = json.loads(cloud_bytes)['bundle']['data']['series']
    require(normalized(cloud_data['document']) == normalized(current), 'Actual DAV file omitted source copies')
    source_copy = find_copy(current, copies['calendar'])
    current, _ = mutate(a, 'PUT', '/copies/' + copies['calendar'],
        {'content': {**source_copy['content'], 'rules': '只改历法也要同步。'}, 'authorStatus': 'draft', 'planet': source_copy['planet']}, current)
    second = api('POST', f'/novels/{a}/writing/cloud/push', {})
    require(first['file'] != second['file'] and (folder / first['file']).read_bytes() == cloud_bytes,
            'Series-only update failed immutable DAV versioning')
    remote_before = series(works['cloud'])
    remote_before, _ = mutate(works['cloud'], 'PUT', '/worlds/' + remote_before['worlds'][0]['uid'],
        {'name': '第二设备的本机世界', 'description': '采用云端前需完整保留'}, remote_before)
    # Simulate a genuine pre-C08 cloud package only in this script's fixture file.
    old_cloud = json.loads(cloud_bytes)
    old_cloud['bundle']['data']['schemaVersion'] = 3
    del old_cloud['bundle']['data']['series']
    original_stat = (folder / first['file']).stat()
    novel_count = len(api('GET', '/novels'))
    try:
        (folder / first['file']).write_text(json.dumps(old_cloud, ensure_ascii=False), encoding='utf-8')
        api('POST', f'/novels/{works["cloud"]}/writing/cloud/pull',
            {'book': workspace['uid'], 'file': first['file']}, expected=409)
        require(series(works['cloud']) == remote_before and len(api('GET', '/novels')) == novel_count,
                'Rejected old cloud package changed series or created an unwanted recovery copy')
    finally:
        (folder / first['file']).write_bytes(cloud_bytes)
        os.utime(folder / first['file'], ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns))
    pulled = api('POST', f'/novels/{works["cloud"]}/writing/cloud/pull', {'book': workspace['uid'], 'file': second['file']})
    require(normalized(series(works['cloud'])) == normalized(current), 'DAV round trip lost copies/provenance')
    require(normalized(series(pulled['copyNovelId'])) == normalized(remote_before), 'DAV recovery copy omitted local world')
    require(template(source_uid) == global_before and series(b) == other, 'DAV pull changed shared head or unrelated novel')

    result = {'status': 'passed', 'database': database, 'works': works, 'templates': templates, 'copies': copies,
              'worldUid': world_uid, 'bookUid': workspace['uid'], 'localRecoveryCopy': restored['copyNovelId'],
              'cloudRecoveryCopy': pulled['copyNovelId'], 'cloudFiles': [first['file'], second['file']],
              'checks': ['five kinds complete UTF-8 copies', 'novel/world isolation', 'source publish CAS',
                         'three-way partial adoption', 'pinned v2 after v3', 'review-only and history restore',
                         'durable replay after later mutation', 'restore epoch invalidates old retry',
                         'complete source export and idempotent import', 'predelete old/malformed backup guards',
                         'world-only legacy protection', 'new work and recovery copies', 'actual immutable DAV round trip']}
    result_path = run / 'series-templates-result.json'
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PASS: C08 complete copies, isolation, versions, adoption, replay, legacy/image guards, backup and real DAV')
    print('Browser fixtures and evidence:', result_path)


if __name__ == '__main__':
    main()
