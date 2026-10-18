#!/usr/bin/env python3
"""Verify AI and story ledgers against integration-check.py's disposable database.

Start the disposable backend with NOVEL_AI_CONFIG_FILE/NOVEL_AI_KEY_FILE pointing
inside a temporary QA directory, and NOVEL_AI_ALLOWED_ORIGINS including
http://127.0.0.1:3001. No real model, key, novel database, or external network is used.
Use --keep to retain the local model fixture for browser verification.
Use --existing-model-port PORT to reuse a running ink-fixture without changing
its configuration or stopping its process. Creates separate works and results.
"""
import argparse
import copy
import datetime
import hashlib
import http.server
import json
import os
import pathlib
import re
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('state', type=pathlib.Path)
parser.add_argument('--keep', action='store_true')
parser.add_argument('--reuse-fixture-config', action='store_true', help='reuse only an earlier ink-fixture local test config')
parser.add_argument('--existing-model-port', type=int, help='use a running loopback ink-fixture without changing its config')
args = parser.parse_args()
if args.existing_model_port is not None and (args.keep or not 1 <= args.existing_model_port <= 65535):
    parser.error('--existing-model-port requires a valid port and cannot be combined with --keep')
state = json.loads(args.state.read_text())
assert re.fullmatch(r'novel_it_[a-zA-Z0-9]+', state['database']), 'Disposable database required'
run = pathlib.Path(state['directory']).resolve(strict=True)
assert args.state.resolve() == run / 'run.json'
assert run.name.startswith('novel-integration-') and (run / 'dav').is_dir(), 'Missing disposable integration workspace'
base = 'http://127.0.0.1:8081/api'
origin = 'http://127.0.0.1:3001'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
session = None


def api(method, path, data=None, expected=200, ai=False, extra=None):
    headers = {'Content-Type': 'application/json'}
    if ai:
        headers.update(Origin=origin)
        if session:
            headers['X-AI-Session'] = session
    headers.update(extra or {})
    request = urllib.request.Request(base + path, method=method, headers=headers,
                                    data=None if data is None else json.dumps(data, ensure_ascii=False).encode())
    try:
        with opener.open(request, timeout=130) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    assert status == expected, f'{method} {path}: expected {expected}, got {status}: {raw[:400].decode()}'
    return json.loads(raw)


novels = api('GET', '/novels')
assert any(row['title'] == 'Legacy integration ' + state['database'] for row in novels), 'Wrong backend'
api('GET', '/ai/session', expected=403, extra={'Origin': 'https://untrusted.example'})
session = api('GET', '/ai/session', ai=True)['token']
config = api('GET', '/ai/config', ai=True)
if args.existing_model_port is not None:
    assert config['configured'] and config['model'] == 'ink-fixture' and config['endpointMode'] == 'local' \
        and config['baseUrl'].rstrip('/') == f'http://127.0.0.1:{args.existing_model_port}/v1', 'Expected running local ink-fixture configuration'
else:
    assert not config['configured'] or (args.reuse_fixture_config and config['model'] == 'ink-fixture' and config['endpointMode'] == 'local' and config['baseUrl'].startswith('http://127.0.0.1:')), 'Refusing to replace an existing AI configuration; use an isolated QA config path'
received = []


class Model(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        raw = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        received.append(raw)
        # A correction preserves system + original user data and appends the
        # failed assistant result + validation feedback. Read the original task.
        content = raw['messages'][1]['content']
        if 'connection test' in raw['messages'][0]['content']:
            result = {'ok': True}
        else:
            value = json.loads(content)
            if 'candidateOutput' in value:
                failed = value['candidateOutput']['directions'][0]['title'] == '待边界复核的方向'
                result = {'allowed': not failed, 'issues': ['dialogue'] if failed else []}
            else:
                action = value['action']
                correcting = len(raw['messages']) == 4
                if correcting:
                    assert [message['role'] for message in raw['messages']] == ['system', 'user', 'assistant', 'user']
                    feedback = json.loads(raw['messages'][3]['content'])
                    assert feedback['type'] == 'validation_feedback' and feedback['correctionRound'] == 1
                    assert feedback['diagnosis'] and raw['messages'][2]['content']
                result = {'candidates': [], 'directions': [], 'issues': []}
                reference = value['context']['novel'][0]['sourceRef']
                if action == 'writing.inspiration':
                    for title, choice in [('承诺的代价', '在兑现承诺与保住身份之间做选择'), ('信息的缺口', '决定先核实线索还是立即行动'), ('关系的试探', '决定是否向盟友透露部分疑虑')]:
                        result['directions'].append({'title': title, 'goal': '推动当前矛盾进入可由人物决定的阶段',
                            'choice': choice, 'consequence': '选择会改变下一步可用的资源', 'risk': '需要核对人物此刻是否知情',
                            'question': '这次选择应付出什么具体代价？', 'sourceRefs': [reference], 'assumptions': []})
                elif action.endswith('.review'):
                    result['issues'].append({'title': '核对动机前提', 'detail': '请确认这个决定与人物此前的目标是否一致。', 'sourceRefs': [reference]})
                else:
                    field = action.split('.')[-1]
                    for index, name in enumerate(['沈望舒', '陆知遥', '林见微']):
                        result['candidates'].append({'title': f'候选 {index + 1}', 'reason': '参考当前作品的克制命名风格。',
                            'fields': {field: name if field == 'name' else f'档案候选{index + 1}：重视承诺，对未知保持审慎。'},
                            'sourceRefs': [reference], 'assumptions': ['这是等待作者采用的新候选。'], 'conflicts': []})
                fixture = value['context'].get('authorRequest')
                if fixture == 'fixture-invalid-output' or fixture == 'fixture-correct-output' and not correcting:
                    result['body'] = 'forbidden extra field'
                if fixture == 'fixture-writing-review' and not correcting:
                    result['directions'][0]['title'] = '待边界复核的方向'
        output = {'choices': [{'message': {'role': 'assistant', 'content': json.dumps(result, ensure_ascii=False)}, 'finish_reason': 'stop'}],
                  'usage': {'prompt_tokens': 100, 'completion_tokens': 80, 'total_tokens': 180}}
        encoded = json.dumps(output, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


server = None
model_port = args.existing_model_port
if model_port is None:
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    model_port = server.server_port
    config = api('PUT', '/ai/config', {'baseUrl': f'http://127.0.0.1:{model_port}/v1', 'model': 'ink-fixture',
                 'endpointMode': 'local', 'expectedRevision': config['revision'], 'apiKey': 'fixture-only-key'}, ai=True)
assert config['hasKey'] and 'apiKey' not in config
api('POST', '/ai/test', {'expectedRevision': config['revision']}, ai=True)
book = api('POST', '/novels', {'title': 'AI 与故事账本验收', 'description': '仅含虚构验收材料'})['id']
other = api('POST', '/novels', {'title': 'AI 作品隔离验证'})['id']
prefix = f'/novels/{book}'
person = api('POST', prefix + '/characters', {'name': '林舟', 'personality': '谨慎而重视承诺', 'background': '守塔人的学生'})
foreign = api('POST', f'/novels/{other}/characters', {'name': '另一部作品的人'})
location = api('POST', prefix + '/map-locations', {'name': '旧灯塔', 'locationType': '地标', 'description': '位于海岸的旧航标'})
event = api('POST', prefix + '/timeline-events', {'title': '等待来信', 'description': '林舟首次怀疑信件途中受阻', 'realOrder': 1})
block = str(uuid.uuid4())
chapter = api('POST', prefix + '/writing/chapters', {'uid': str(uuid.uuid4()), 'title': '第一章 雾中的约定',
    'doc': {'type': 'doc', 'content': [{'type': 'paragraph', 'attrs': {'id': block}, 'content': [{'type': 'text', 'text': '林舟在旧灯塔等候迟来的信。'}]}]}})
later = api('POST', prefix + '/writing/chapters', {'uid': str(uuid.uuid4()), 'title': '第二章 尚未抵达的消息'})
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
source = {'chapterUid': chapter['uid'], 'blockId': block, 'excerpt': '林舟在旧灯塔等候迟来的信。', 'eventId': event['id'], 'eventTitle': event['title'], 'note': '验收：作者显式记录'}
record = {'uid': str(uuid.uuid4()), 'storyOrder': 1, 'narrativeOrder': 1, 'source': source, 'recordedAt': now, 'retracted': False}
desk = api('GET', prefix + '/writing/desk')
desk['storyState'] = {'schemaVersion': 1, 'states': [{**record, 'characterId': person['id'], 'characterName': person['name'], 'field': '地点', 'value': '旧灯塔'}],
    'secrets': [{'uid': str(uuid.uuid4()), 'title': '信件被截留', 'truth': '信件尚未送达', 'recordedAt': now, 'archived': False,
        'knowledge': [{**record, 'uid': str(uuid.uuid4()), 'characterId': person['id'], 'characterName': person['name'], 'state': 'suspect', 'belief': '怀疑信件途中受阻'}],
        'reader': [{**record, 'uid': str(uuid.uuid4()), 'state': 'hinted', 'detail': '读者只看到迟来的迹象'}]}]}
desk['mutationId'] = str(uuid.uuid4())
saved = api('PUT', prefix + '/writing/desk', desk)
assert api('PUT', prefix + '/writing/desk', desk) == saved, 'Story retry is not idempotent'
old = {k: v for k, v in saved.items() if k != 'storyState'}
old['mutationId'] = str(uuid.uuid4())
api('PUT', prefix + '/writing/desk', old, expected=409)


def suggest(action, context, expected=200, revision=None):
    return api('POST', prefix + '/ai/suggestions', {'action': action, 'context': context, 'requestId': str(uuid.uuid4()),
               'expectedConfigRevision': revision or config['revision']}, expected=expected, ai=True)


before = len(received)
name_context = {'targetId': person['id'], 'targetDraft': {'name': '林舟', 'portraitImage': 'https://not-sent.example/img'}}
names = suggest('character.name', name_context)
if server:
    assert len(received) == before + 1, 'Setting generation unexpectedly requested a boundary review'
    assert 'not-sent.example' not in json.dumps(received[before])
    assert '林舟在旧灯塔等候迟来的信。' not in received[before]['messages'][1]['content'], 'Unselected manuscript or story excerpts entered settings context'
assert names['coverage']['mode'] == 'settings' and len(names['result']['candidates']) == 3
assert names['attemptCount'] == 1 and names['correctionApplied'] is False
assert api('GET', prefix + '/characters')[0]['name'] == '林舟', 'AI changed character without adoption'


def context_current(result, context, novel_id=book, expected=200):
    before_check = len(received)
    checked = api('POST', f'/novels/{novel_id}/ai/context-check', {'action': result['action'],
                  'context': context, 'snapshotId': result['snapshotId']}, expected=expected, ai=True)
    if server:
        assert len(received) == before_check, 'Context check unexpectedly contacted the model'
    return checked.get('current')


assert context_current(names, name_context) is True, 'Newly generated source snapshot is already stale'
api('PUT', f'/novels/{other}/characters/{foreign["id"]}', {**foreign, 'personality': '隔离作品中新增的描述'})
assert context_current(names, name_context) is True, 'Another work invalidated this work snapshot'
context_current(names, name_context, novel_id=other, expected=404)
assert context_current(names, {}, novel_id=other) is False, 'Source snapshot leaked across works'
assert all(item['id'] != f'characters:{foreign["id"]}' for item in names['sources']), 'Foreign source entered the context'
api('PUT', prefix + f'/characters/{person["id"]}', {**person, 'personality': '谨慎而重视承诺，但开始质疑导师'})
assert context_current(names, name_context) is False, 'Changed related source did not invalidate old candidates'
suggest('character.name', {'targetId': foreign['id']}, expected=404)
before = len(received)
failed = suggest('character.name', {'targetId': person['id'], 'request': 'fixture-invalid-output'}, expected=422)
assert failed['code'] == 'AI_CORRECTION_FAILED'
if server:
    assert len(received) == before + 2, 'Invalid setting output must stop after one correction'
    assert received[before]['messages'] == received[before + 1]['messages'][:2], 'Correction changed initial context'
before = len(received)
corrected = suggest('character.name', {'targetId': person['id'], 'request': 'fixture-correct-output'})
assert corrected['attemptCount'] == 2 and corrected['correctionApplied'] and corrected['correctionReason']
if server:
    assert len(received) == before + 2
    assert received[before]['messages'] == received[before + 1]['messages'][:2]
    assert 'forbidden extra field' in received[before + 1]['messages'][2]['content']
suggest('character.name', {'targetId': person['id']}, expected=409, revision='stale-version')
draft = copy.deepcopy(chapter)
draft['doc']['content'][0]['content'][0]['text'] += ' 这是尚未保存的线索。'
writing_context = {'chapterUid': chapter['uid'], 'currentDraft': draft}
before = len(received)
suggest('writing.inspiration', {**writing_context, 'referenceChapterUids': [chapter['uid']]}, expected=400)
assert len(received) == before, 'Writing workbench incorrectly accepted the settings-only selection scope'
before = len(received)
directions = suggest('writing.inspiration', writing_context)
assert directions['coverage']['includesUnsavedDraft'] and directions['coverage']['chaptersAfter'] == 1
assert len(directions['result']['directions']) == 3
if server:
    assert len(received) == before + 2, 'Writing requires generation and a boundary review'
    assert len(received[before]['messages']) == 2
    assert '尚未保存的线索' in received[before]['messages'][1]['content']
before = len(received)
repaired_writing = suggest('writing.inspiration', {**writing_context, 'request': 'fixture-writing-review'})
assert repaired_writing['attemptCount'] == 2 and repaired_writing['correctionApplied']
assert '对白' in repaired_writing['correctionReason']
if server:
    assert len(received) == before + 4, 'Writing review failure must use exactly one correction and one final review'
    assert received[before]['messages'] == received[before + 2]['messages'][:2]
    assert json.loads(received[before + 2]['messages'][3]['content'])['diagnosis'] == repaired_writing['correctionReason']
assert '尚未保存的线索' not in json.dumps(api('GET', prefix + '/writing/chapters/' + chapter['uid']), ensure_ascii=False)
assert any(item['type'] == 'storyState' for item in directions['sources'])
assert context_current(directions, writing_context) is True, 'Fresh writing snapshot is not current'
changed_context = copy.deepcopy(writing_context)
changed_context['currentDraft']['doc']['content'][0]['content'][0]['text'] += ' 又一处未保存的改变。'
assert context_current(directions, changed_context) is False, 'Unsaved draft change did not invalidate inspiration'
changed_desk = copy.deepcopy(saved)
changed_desk['mutationId'] = str(uuid.uuid4())
changed_desk['storyState']['secrets'][0]['knowledge'].append({**record, 'uid': str(uuid.uuid4()),
    'storyOrder': 2, 'narrativeOrder': 3, 'characterId': person['id'], 'characterName': person['name'],
    'state': 'know', 'belief': '作者记录：已确认信件被截留'})
changed_desk['storyState']['secrets'][0]['reader'].append({**record, 'uid': str(uuid.uuid4()),
    'storyOrder': 3, 'narrativeOrder': 2, 'state': 'revealed', 'detail': '读者先于角色看到截留者的记录'})
saved = api('PUT', prefix + '/writing/desk', changed_desk)
assert context_current(directions, writing_context) is False, 'Ledger change did not invalidate writing snapshot'


def verify_action_matrix():
    """Match real form shapes and verify every setting's exact source projection."""
    matrix_book = api('POST', '/novels', {'title': 'AI 全动作矩阵', 'description': '所有资料字段和未保存关联验收', 'genre': '悬疑'})['id']
    scope = f'/novels/{matrix_book}'
    tag = api('POST', scope + '/tags', {'name': '承诺', 'description': '选择必须付出代价'})

    def form(resource, values):
        return api('POST', scope + '/forms/' + resource, {'data': values}, extra={'Idempotency-Key': str(uuid.uuid4())})

    actor = form('characters', {'name': '周遥', 'role': '信使', 'personality': '守信但多疑', 'appearance': '右眼下有旧疤',
                               'background': '曾错送一封重要信件', 'portraitImage': 'https://not-sent.example/actor'})
    companion = form('characters', {'name': '宋宁', 'role': '守塔人', 'personality': '谨慎', 'appearance': '常戴旧手套', 'background': '负责灯塔维护'})
    parent = form('map-locations', {'name': '西海岸', 'locationType': '区域', 'description': '雾多而潮急', 'positionX': 5, 'positionY': 9})
    place = form('map-locations', {'name': '回声塔', 'locationType': '地标', 'description': '旧信使的中转站', 'positionX': 17, 'positionY': 23})
    scene = form('scenes', {'name': '塔顶会面', 'location': '灯塔顶层', 'atmosphere': '紧张而克制', 'description': '交换消息的场所',
                            'sceneImage': 'https://not-sent.example/scene'})
    world = form('worldview', {'name': '信使戒律', 'category': 'society', 'content': '信件交付需要双方见证',
                              'entryImage': 'https://not-sent.example/world'})
    outline = form('outlines', {'title': '调查迟来的信', 'content': '先验证线索，再决定是否履约', 'chapterNumber': 2, 'plotOrder': 3, 'status': 'planning'})
    foreshadow = form('foreshadows', {'title': '褪色印章', 'content': '同一印章可能被使用两次', 'laidAt': '第一章', 'revealedAt': '第五章', 'status': 'pending'})
    timeline = form('timeline-events', {'title': '约定前夜', 'description': '人物决定暂缓交信', 'eventTime': '第三日黄昏', 'realOrder': 4})
    relation = form('relationships', {'characterId1': actor['id'], 'characterId2': companion['id'], 'relationshipType': '旧识', 'description': '互有亏欠'})
    group = api('POST', scope + '/relationship-groups', {'name': '守信者', 'description': '共同遵守戒律', 'characterIds': [actor['id'], companion['id']]})
    chapter = api('POST', scope + '/writing/chapters', {'uid': str(uuid.uuid4()), 'title': '第二章 门前',
        'summary': '本章目标：让人物面对代价', 'doc': {'type': 'doc', 'content': [{'type': 'paragraph', 'attrs': {'id': str(uuid.uuid4())},
        'content': [{'type': 'text', 'text': '周遥停在门前，等待约定的信号。'}]}]}})
    paths = ['characters', 'map-locations', 'scenes', 'worldview', 'outlines', 'timeline-events', 'foreshadows',
             'relationships', 'relationship-groups', 'tags', 'writing/desk', 'writing/chapters/' + chapter['uid']]
    before_matrix = {path: api('GET', scope + '/' + path) for path in paths}
    specs = {
        'characters': ('name,description,personality,appearance,background,role', 'tags'),
        'map': ('name,description,locationType,positionX,positionY', 'parentLocation,tags'),
        'scenes': ('name,description,location,atmosphere', 'mapLocations,tags'),
        'worldview': ('name,category,content', 'characters,scenes,mapLocations,tags'),
        'outlines': ('title,content,chapterNumber,plotOrder,status', 'tags'),
        'timeline': ('title,description,eventTime,realOrder', 'characters,scenes,foreshadows,outlines,tags'),
        'foreshadows': ('title,content,laidAt,revealedAt,status', 'tags'),
        'relationships': ('characterId1,characterId2,relationshipType,description', ''),
        'relationshipGroups': ('name,description,parentGroupId', 'characters'),
        'tags': ('name,description', ''),
    }
    entities = [('characters', actor), ('characters', companion), ('map', parent), ('map', place), ('scenes', scene),
                ('worldview', world), ('outlines', outline), ('timeline', timeline), ('foreshadows', foreshadow),
                ('relationships', relation), ('relationshipGroups', group), ('tags', tag)]

    def projection(kind, entity):
        fields, references = specs[kind]
        value = {'id': entity['id'], **{field: entity.get(field) for field in fields.split(',')}}
        for key in filter(None, references.split(',')):
            link = entity.get(key)
            value[key] = sorted(item['id'] for item in link) if isinstance(link, list) else (link['id'] if link else None)
        return value

    def source_hash(value):
        return hashlib.sha256(json.dumps(value, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()

    expected_sources = {f'{kind}:{entity["id"]}': source_hash(projection(kind, entity)) for kind, entity in entities}
    drafts = {
        'character': {'name': '未保存的周遥', 'role': '游走信使', 'personality': '守信却不肯轻信', 'appearance': '旧疤和新斗篷',
                      'background': '曾为守约付出代价', 'portraitImage': 'https://not-sent.example/draft', 'tagIds': [tag['id']]},
        'location': {'name': '未命名的塔', 'locationType': '地标', 'description': '位于海岸的秘密中转站',
                     'parentLocationId': str(parent['id']), 'positionX': 31, 'positionY': 47},
        'scene': {'name': '未保存会面', 'location': '塔顶小室', 'atmosphere': '压抑', 'description': '用于试探盟友',
                  'sceneImage': 'https://not-sent.example/draft', 'tagIds': [tag['id']]},
        'worldview': {'name': '未保存戒律', 'category': 'society', 'content': '违约会失去通行资格', 'entryImage': 'https://not-sent.example/draft',
                      'tagIds': [tag['id']], 'characterIds': [actor['id']], 'sceneIds': [scene['id']], 'mapLocationIds': [place['id']]},
        'outline': {'title': '未保存计划', 'content': '在核实消息后选择代价', 'status': 'planning', 'chapterNumber': 3, 'plotOrder': 5, 'tagIds': [tag['id']]},
        'timeline': {'title': '未保存事件', 'eventTime': '第三日夜间', 'realOrder': 5, 'description': '角色重新考虑承诺', 'tagIds': [tag['id']]},
        'foreshadow': {'title': '未保存线索', 'content': '印章有细微缺口', 'status': 'pending', 'laidAt': '第二章', 'revealedAt': '第六章', 'tagIds': [tag['id']]},
        'relationship': {'characterId1': str(actor['id']), 'characterId2': str(companion['id']), 'relationshipType': '互相试探', 'description': '双方期待不同回报'},
    }
    targets = {'character': ('characters', actor), 'location': ('map', place), 'scene': ('scenes', scene), 'worldview': ('worldview', world),
               'outline': ('outlines', outline), 'timeline': ('timeline', timeline), 'foreshadow': ('foreshadows', foreshadow), 'relationship': ('relationships', relation)}
    actions = ['character.name', 'character.personality', 'character.background', 'character.appearance', 'location.name', 'location.description',
               'scene.name', 'scene.description', 'worldview.content', 'outline.review', 'timeline.review', 'foreshadow.review', 'relationship.review', 'writing.inspiration']
    reference_fields = {'tagIds': 'tags', 'characterIds': 'characters', 'sceneIds': 'scenes', 'mapLocationIds': 'mapLocations'}
    for action in actions:
        scope_name, field = action.split('.')
        if action == 'writing.inspiration':
            local = copy.deepcopy(chapter)
            local['doc']['content'][0]['content'][0]['text'] += ' 这是矩阵中的未保存进度。'
            context = {'chapterUid': chapter['uid'], 'currentDraft': local}
        else:
            kind, entity = targets[scope_name]
            draft = drafts[scope_name]
            context = {'targetId': entity['id'], 'targetDraft': draft, 'targetField': '' if field == 'review' else field}
        before_calls = len(received)
        response = api('POST', scope + '/ai/suggestions', {'action': action, 'context': context,
            'requestId': str(uuid.uuid4()), 'expectedConfigRevision': config['revision']}, ai=True)
        if server:
            assert len(received) == before_calls + (2 if action == 'writing.inspiration' else 1), f'{action}: wrong generation/review call count'
        result = response['result']
        assert set(result) == {'candidates', 'directions', 'issues'}, action
        if action == 'writing.inspiration':
            assert len(result['directions']) == 3 and not result['candidates'] and not result['issues'], action
        elif field == 'review':
            assert result['issues'] and not result['candidates'] and not result['directions'], action
            assert all(set(issue) == {'title', 'detail', 'sourceRefs'} for issue in result['issues']), action
        else:
            assert len(result['candidates']) == 3 and not result['directions'] and not result['issues'], action
            assert all(set(candidate['fields']) == {field} for candidate in result['candidates']), action
        source_rows = {row['id']: row for row in response['sources']}
        for reference, expected_hash in expected_sources.items():
            assert source_rows[reference]['hash'] == expected_hash, f'{action}: missing or incomplete {reference}'
        if action != 'writing.inspiration':
            expected_target = projection(kind, entity)
            for key in specs[kind][0].split(','):
                if key in draft:
                    expected_target[key] = int(draft[key]) if key in ('characterId1', 'characterId2') else draft[key]
            for input_key, output_key in reference_fields.items():
                if input_key in draft:
                    expected_target[output_key] = sorted(draft[input_key])
            if 'parentLocationId' in draft:
                expected_target['parentLocation'] = int(draft['parentLocationId']) if draft['parentLocationId'] else None
            assert source_rows['target-draft']['hash'] == source_hash(expected_target), f'{action}: incomplete current form or associations'
        assert context_current(response, context, novel_id=matrix_book) is True, action
        if action != 'writing.inspiration' and 'tagIds' in context['targetDraft']:
            changed = copy.deepcopy(context)
            changed['targetDraft']['tagIds'] = []
            assert context_current(response, changed, novel_id=matrix_book) is False, f'{action}: ignored unsaved tags'
    assert {path: api('GET', scope + '/' + path) for path in paths} == before_matrix, 'An AI action mutated author data'
    cross_work = {'targetId': world['id'], 'targetDraft': {**drafts['worldview'], 'characterIds': [person['id']]}}
    api('POST', scope + '/ai/suggestions', {'action': 'worldview.content', 'context': cross_work,
        'requestId': str(uuid.uuid4()), 'expectedConfigRevision': config['revision']}, expected=400, ai=True)
    return {'novelId': matrix_book, 'actions': actions, 'completeSettingSources': len(expected_sources),
            'checks': ['exact candidate field allowlists', 'review issues only', 'three writing directions', 'no persisted mutations',
                       'every setting field and reference hashed', 'complete current form and unsaved associations', 'draft tags invalidate snapshot',
                       'cross-work unsaved associations rejected']}


def verify_selected_manuscript():
    """Exercise selection against real saved chapters without reading user works."""
    chosen_book = api('POST', '/novels', {'title': 'AI 明确选章边界验收'})['id']
    foreign_book = api('POST', '/novels', {'title': 'AI 选章跨作品隔离'})['id']
    scope = f'/novels/{chosen_book}'

    def chapter_in(novel_id, title, text, **extra):
        return api('POST', f'/novels/{novel_id}/writing/chapters', {
            'uid': str(uuid.uuid4()), 'title': title, **extra,
            'doc': {'type': 'doc', 'content': [{'type': 'paragraph', 'attrs': {'id': str(uuid.uuid4())},
                    'content': [{'type': 'text', 'text': text}]}]},
        })

    selected_text = '甲乙 hello world 12 😀！'
    selected = chapter_in(chosen_book, '明确选择的章节', selected_text,
                          summary='不能发送的章节目标', notes='不能发送的章节便笺')
    unselected = chapter_in(chosen_book, '未选择章节', '未选择正文独有标记')
    trashed = chapter_in(chosen_book, '回收站章节', '回收站正文独有标记', deleted=True)
    foreign_chapter = chapter_in(foreign_book, '其他作品章节', '其他作品正文独有标记')
    exact = chapter_in(chosen_book, '恰好一万字', '界' * 10000)
    extra_word = chapter_in(chosen_book, '再多一字', '界')
    punctuation = chapter_in(chosen_book, '零计数字符仍占输入预算', '！' * (config['maxInputChars'] + 1000))
    before_list = len(received)
    listing = api('GET', scope + '/ai/reference-chapters', ai=True)
    assert listing['maxChars'] == 10000
    rows = {row['uid']: row for row in listing['chapters']}
    assert set(rows) == {row['uid'] for row in [selected, unselected, exact, extra_word, punctuation]}
    assert all(set(row) == {'uid', 'title', 'wordCount'} for row in rows.values()), 'Chapter picker leaked body content'
    assert rows[selected['uid']]['wordCount'] == selected['wordCount'] == 5, 'Picker counting differs from writer CJK/word rules'
    assert rows[exact['uid']]['wordCount'] == 10000 and rows[punctuation['uid']]['wordCount'] == 0
    assert len(received) == before_list, 'Listing chapter references contacted a model'

    def selected_suggest(context, expected=200, expected_calls=1):
        start = len(received)
        result = api('POST', scope + '/ai/suggestions', {
            'action': 'character.name', 'context': context, 'requestId': str(uuid.uuid4()),
            'expectedConfigRevision': config['revision'],
        }, expected=expected, ai=True)
        if server:
            assert len(received) == start + (expected_calls if expected == 200 else 0), 'Selection rejection reached model or generation unexpectedly retried'
        payload = json.loads(received[start]['messages'][1]['content']) if server and expected == 200 else None
        return result, payload

    none, none_payload = selected_suggest({})
    assert none['coverage']['selectedChapterCount'] == none['coverage']['selectedManuscriptChars'] == 0
    assert none['coverage']['progress'] == 'selected-reference', 'Settings reference implied a writing progress anchor'
    assert not any(row['type'] == 'chapter' for row in none['sources'])
    if server:
        assert none_payload['context']['selectedManuscript'] == []
        assert selected_text not in json.dumps(none_payload, ensure_ascii=False)
        assert 'manuscript' not in none_payload['context'] and 'authorPlans' not in none_payload['context']
    assert context_current(none, {'referenceChapterUids': []}, novel_id=chosen_book) is True, 'Explicit empty selection differs from default none'

    choice = {'referenceChapterUids': [selected['uid']]}
    result, payload = selected_suggest(choice)
    assert result['coverage']['selectedChapterCount'] == 1 and result['coverage']['selectedManuscriptChars'] == 5
    assert result['coverage']['includesUnsavedDraft'] is False
    chapter_sources = [row for row in result['sources'] if row['type'] == 'chapter']
    assert len(chapter_sources) == 1 and chapter_sources[0]['id'] == 'chapter:' + selected['uid']
    assert chapter_sources[0]['revision'] == selected['revision']
    if server:
        context = payload['context']
        assert len(context['selectedManuscript']) == 1
        content = context['selectedManuscript'][0]['content']
        assert set(content) == {'chapterUid', 'title', 'wordCount', 'savedOnly', 'blocks'}
        assert content['savedOnly'] and content['blocks'][0]['text'] == selected_text
        sent = json.dumps(context, ensure_ascii=False)
        for marker in ['未选择正文独有标记', '回收站正文独有标记', '其他作品正文独有标记', '不能发送的章节目标', '不能发送的章节便笺']:
            assert marker not in sent, f'Unselected or non-body material leaked: {marker}'
        assert 'manuscript' not in context and 'authorPlans' not in context
    assert context_current(result, choice, novel_id=chosen_book) is True
    assert context_current(result, {}, novel_id=chosen_book) is False, 'Clearing selection did not invalidate old output'
    before_correction = len(received)
    corrected, _ = selected_suggest({**choice, 'request': 'fixture-correct-output'}, expected_calls=2)
    assert corrected['correctionApplied'] and corrected['coverage']['selectedManuscriptChars'] == 5
    if server:
        assert received[before_correction]['messages'] == received[before_correction + 1]['messages'][:2], 'Correction changed selected manuscript or its original author context'
    unselected_edit = copy.deepcopy(unselected)
    unselected_edit['mutationId'] = str(uuid.uuid4())
    unselected_edit['doc']['content'][0]['content'][0]['text'] += ' 新增但仍未选择的正文。'
    api('PUT', scope + '/writing/chapters/' + unselected['uid'], unselected_edit)
    assert context_current(result, choice, novel_id=chosen_book) is True, 'Unselected body update contaminated selected snapshot'
    selected_edit = copy.deepcopy(selected)
    selected_edit['mutationId'] = str(uuid.uuid4())
    selected_edit['doc']['content'][0]['content'][0]['text'] += ' 丙'
    api('PUT', scope + '/writing/chapters/' + selected['uid'], selected_edit)
    assert context_current(result, choice, novel_id=chosen_book) is False, 'Saved selected body update did not invalidate snapshot'

    boundary, boundary_payload = selected_suggest({'referenceChapterUids': [exact['uid']]})
    assert boundary['coverage']['selectedManuscriptChars'] == 10000
    if server:
        assert boundary_payload['context']['selectedManuscript'][0]['content']['blocks'][0]['text'] == '界' * 10000, 'Exactly allowed body was truncated'
    selected_suggest({'referenceChapterUids': [exact['uid'], extra_word['uid']]}, 413)
    selected_suggest({'referenceChapterUids': [punctuation['uid']]}, 413)
    for invalid_uid in [trashed['uid'], foreign_chapter['uid'], str(uuid.uuid4())]:
        selected_suggest({'referenceChapterUids': [invalid_uid]}, 404)
    selected_suggest({'referenceChapterUids': [exact['uid'], exact['uid']]}, 400)
    selected_suggest({'referenceChapterUids': 'not-an-array'}, 400)
    selected_suggest({'referenceChapterUids': [selected['uid']], 'currentDraft': selected_edit}, 400)
    return {'novelId': chosen_book, 'checks': ['metadata-only picker without model calls', 'writer-compatible word count',
        'default none and explicit empty equivalent', 'only explicitly selected saved bodies sent', 'no notes or goals forwarded',
        'selected source update invalidates snapshot', 'unselected body update leaves snapshot current',
        '10000 accepted whole and 10001 rejected before model', 'full character input budget still enforced',
        'foreign trashed missing duplicate and malformed selection rejected before model', 'unsaved manuscript override rejected']}


action_matrix = verify_action_matrix()
selected_manuscript = verify_selected_manuscript()
backup = api('GET', prefix + '/backup')
assert 'fixture-only-key' not in json.dumps(backup) and 'encryptedKey' not in json.dumps(backup)
api('POST', f'/novels/{other}/backup', backup)
restored = api('GET', f'/novels/{other}/writing/desk')['storyState']
restored_people = api('GET', f'/novels/{other}/characters')
assert restored['states'][0]['characterId'] == restored_people[0]['id'] != person['id'], 'Restore did not remap person'
assert restored['secrets'][0]['knowledge'][0]['characterId'] == restored_people[0]['id']
assert restored['secrets'][0]['reader'][0]['state'] == 'hinted'
restored_events = api('GET', f'/novels/{other}/timeline-events')
assert restored['states'][0]['source']['eventId'] == restored_events[0]['id'] != event['id'], 'Restore did not remap source event'
legacy = copy.deepcopy(backup)
del legacy['data']['writing']['desk']['storyState']
api('POST', prefix + '/backup', legacy, expected=400)
assert api('GET', prefix + '/writing/desk')['storyState'] == saved['storyState']

# Reuse the integration fixture's already-encrypted WebDAV settings only inside
# its marker-verified disposable database. Do not decrypt or print credentials.
dav_source = next((row for row in sorted(novels, key=lambda row: row['id'])
                   if row['title'] == '持续优化验证' and row.get('webdavServerUrl')), None)
assert dav_source, 'Run integration-check.py first to create the WebDAV fixture'
dav_url = urllib.parse.urlparse(dav_source['webdavServerUrl'])
assert dav_url.scheme == 'http' and dav_url.hostname == '127.0.0.1', 'Refusing non-loopback WebDAV fixture'
root = pathlib.Path(__file__).resolve().parents[1]
props = dict(line.split('=', 1) for line in (root / 'backend/src/main/resources/application.properties')
             .read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
environment = os.environ.copy()
environment['PGPASSWORD'] = os.environ.get('PGPASSWORD', props['spring.datasource.password'])
pg = pathlib.Path(os.environ.get('PG_BIN', '/Library/PostgreSQL/18/bin'))
pg_user = os.environ.get('PGUSER', props['spring.datasource.username'])


def sql(command):
    result = subprocess.run([str(pg / 'psql'), '-h', 'localhost', '-U', pg_user, '-d', state['database'],
                             '-v', 'ON_ERROR_STOP=1', '-Atc', command], env=environment, capture_output=True, text=True)
    assert result.returncode == 0, 'Disposable database SQL check failed; credentials were not printed'
    return result.stdout.strip()


assert sql(f"SELECT EXISTS (SELECT 1 FROM novels WHERE title='Legacy integration {state['database']}')") == 't', 'SQL database marker missing'
dav_source_id = int(dav_source['id'])
assert sql(f"SELECT webdav_password LIKE 'enc:v1:%' FROM novels WHERE id={dav_source_id}") == 't', 'Expected encrypted WebDAV fixture'
dav_target = api('POST', '/novels', {'title': '故事账本 WebDAV 恢复验证'})['id']
cloud_target = api('POST', '/novels', {'title': '故事账本写作云恢复验证'})['id']
sql(f'UPDATE novels SET webdav_server_url=s.webdav_server_url, webdav_username=s.webdav_username, '
    f'webdav_password=s.webdav_password, webdav_auto_sync=false FROM '
    f'(SELECT * FROM novels WHERE id={dav_source_id}) s WHERE novels.id IN ({int(book)}, {int(dav_target)}, {int(cloud_target)})')


def verify_restored_ledger(target):
    target_prefix = f'/novels/{target}'
    actual = api('GET', target_prefix + '/writing/desk')['storyState']
    people = api('GET', target_prefix + '/characters')
    events = api('GET', target_prefix + '/timeline-events')
    assert len(people) == len(events) == 1 and people[0]['id'] != person['id'] and events[0]['id'] != event['id']
    expected = copy.deepcopy(saved['storyState'])
    histories = [expected['states']]
    for secret in expected['secrets']:
        histories.extend([secret['knowledge'], secret['reader']])
    for history in histories:
        for item in history:
            if 'characterId' in item:
                item['characterId'] = people[0]['id']
            item['source']['eventId'] = events[0]['id']
    assert actual == expected, 'WebDAV restore changed ledger history, order, knowledge, or source anchors'
    target_chapter = api('GET', target_prefix + '/writing/chapters/' + chapter['uid'])
    assert target_chapter['doc']['content'][0]['attrs']['id'] == block, 'Restored paragraph source no longer resolves'


uploaded = api('POST', prefix + '/webdav/sync/upload', {})
assert uploaded['success'] and pathlib.Path(uploaded['filename']).name == uploaded['filename']
backup_folder = run / 'dav' / 'novel-backups'
remote_backup = json.loads((backup_folder / uploaded['filename']).read_text(encoding='utf-8'))
assert remote_backup['data']['writing']['desk']['storyState'] == saved['storyState'], 'Actual WebDAV file lost ledger data'
downloaded = api('POST', f'/novels/{dav_target}/webdav/sync/download', {'filename': uploaded['filename']})
assert downloaded['success'] and downloaded['copyNovelId'] not in (book, dav_target)
verify_restored_ledger(dav_target)

# Plant an old-format fixture in the disposable WebDAV filesystem and retrieve it
# through the real HTTP restore path. A blocked restore must not create a copy or
# replace any part of the current ledger.
legacy_remote = copy.deepcopy(remote_backup)
del legacy_remote['data']['writing']['desk']['storyState']
legacy_name = f'ai-story-legacy-{uuid.uuid4()}.backup.json'
(backup_folder / legacy_name).write_text(json.dumps(legacy_remote, ensure_ascii=False), encoding='utf-8')
before_books = {row['id'] for row in api('GET', '/novels')}
blocked = api('POST', prefix + '/webdav/sync/download', {'filename': legacy_name})
assert blocked['success'] is False, 'Legacy WebDAV restore unexpectedly succeeded'
assert '人物状态和知情账' in blocked.get('message', ''), 'Legacy WebDAV restore did not explain the ledger protection'
assert api('GET', prefix + '/writing/desk')['storyState'] == saved['storyState']
assert {row['id'] for row in api('GET', '/novels')} == before_books, 'Blocked restore created an unwanted copy'

cloud_upload = api('POST', prefix + '/writing/cloud/push', {})
workspace = api('GET', prefix + '/writing')
assert workspace['syncedSequence'] == workspace['changeSequence'], 'Ledger upload did not acknowledge pending changes'
assert pathlib.Path(cloud_upload['file']).name == cloud_upload['file']
cloud_folder = backup_folder / ('ink-' + workspace['uid'])
cloud_file = json.loads((cloud_folder / cloud_upload['file']).read_text(encoding='utf-8'))
assert cloud_file['bundle']['data']['writing']['desk']['storyState'] == saved['storyState'], 'Cloud revision lost ledger data'
cloud_download = api('POST', f'/novels/{cloud_target}/writing/cloud/pull', {'book': workspace['uid'], 'file': cloud_upload['file']})
assert cloud_download['copyNovelId'] not in (book, cloud_target)
verify_restored_ledger(cloud_target)

legacy_cloud = copy.deepcopy(cloud_file)
del legacy_cloud['bundle']['data']['writing']['desk']['storyState']
legacy_revision = str(uuid.uuid4())
legacy_cloud['revision'] = legacy_revision
legacy_cloud['parent'] = cloud_file['revision']
legacy_cloud_name = f'{datetime.datetime.now().strftime("%Y%m%d%H%M%S")}_{legacy_revision}_{cloud_file["revision"]}.ink.json'
(cloud_folder / legacy_cloud_name).write_text(json.dumps(legacy_cloud, ensure_ascii=False), encoding='utf-8')
before_books = {row['id'] for row in api('GET', '/novels')}
blocked = api('POST', prefix + '/writing/cloud/pull', {'book': workspace['uid'], 'file': legacy_cloud_name}, expected=400)
assert '人物状态和知情账' in blocked.get('message', ''), 'Legacy cloud pull did not explain the ledger protection'
assert api('GET', prefix + '/writing/desk')['storyState'] == saved['storyState']
assert {row['id'] for row in api('GET', '/novels')} == before_books, 'Blocked cloud pull created an unwanted copy'
assert api('GET', '/ai/config', ai=True) == config, 'Test changed the existing provider configuration'

result = {'status': 'passed', 'database': state['database'], 'novelId': book, 'restoredNovelId': other, 'characterId': person['id'], 'locationId': location['id'],
          'chapterUid': chapter['uid'], 'modelPort': model_port, 'modelRequests': len(received) if server else None,
          'providerMode': 'started fixture' if server else 'existing fixture, configuration unchanged',
          'webdavRestoredNovelId': dav_target, 'cloudRestoredNovelId': cloud_target,
          'webdavFile': uploaded['filename'], 'cloudFile': cloud_upload['file'],
          'actionMatrix': action_matrix,
          'selectedManuscript': selected_manuscript,
          'checks': ['untrusted origin rejection', 'small model test',
                     'single-field no writes', 'cross-work rejection', 'invalid output rejection', 'recipient revision binding',
                     'unsaved manuscript context without save', 'story knowledge context', 'story idempotency', 'legacy client protection',
                     'fresh context current', 'related source invalidates context', 'other work changes do not invalidate context',
                     'context snapshot cross-work isolation', 'unsaved draft invalidates context', 'ledger invalidates context',
                     'complete backup and person/event ID remapping', 'legacy backup protection', 'credentials excluded from backup',
                     'actual WebDAV backup ledger round trip', 'actual cloud revision ledger round trip',
                     'legacy WebDAV restore protection', 'legacy cloud pull protection', 'provider configuration unchanged']}
if server:
    result['checks'].extend(['encrypted config round trip', 'scope-specific review calls', 'one correction with preserved context',
                             'writing boundary rejection and final recheck', 'invalid output stops after one correction', 'excluded portrait payload'])
result_path = run / ('ai-story-extended-result.json' if args.existing_model_port else 'ai-story-result.json')
result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print('PASS: AI context freshness/isolation and story-ledger PostgreSQL + WebDAV + cloud round trips', flush=True)
print('Result:', result_path, flush=True)
if args.keep:
    print('Local model fixture is available for browser QA; stop this process when finished.', flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        pass
if server:
    server.shutdown()
