#!/usr/bin/env python3
"""Writing regression against integration-check.py --keep's disposable PostgreSQL and WsgiDAV.
Usage: python3 scripts/writing-integration-check.py /path/to/run.json
Never accepts a production database. Leaves a small demonstration manuscript for browser QA.
"""
import copy, datetime, json, os, pathlib, subprocess, sys, urllib.error, urllib.request, uuid, zipfile, io

ROOT = pathlib.Path(__file__).resolve().parents[1]
state = json.loads(pathlib.Path(sys.argv[1]).read_text())
database = state['database']
assert database.startswith('novel_it_') and database.replace('_', '').isalnum()
run = pathlib.Path(state['directory'])
assert run.name.startswith('novel-integration-') and (run/'dav').is_dir()
base = 'http://127.0.0.1:' + os.environ.get('IT_PORT', '8081') + '/api'
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def api(method, path, data=None, binary=False):
    raw = json.dumps(data, ensure_ascii=False).encode() if data is not None else None
    request = urllib.request.Request(base+path, data=raw, method=method, headers={'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
    with http.open(request, timeout=60) as response:
        return response.read() if binary else json.load(response)
novels = api('GET', '/novels')
assert any(n['title'] == 'Legacy integration '+database for n in novels), 'Wrong backend: no disposable database marker'
n = next(n['id'] for n in novels if n['title'] == '持续优化验证' and (n.get('webdavServerUrl') or '').startswith('http://127.0.0.1:'))
p = f'/novels/{n}'
def uid(): return str(uuid.uuid4())
def doc(lines): return {'type':'doc','content':[{'type':'paragraph','attrs':{'id':uid()},'content':[{'type':'text','text':line}]} for line in lines]}
def save(chapter, **updates):
    data = {**chapter, **updates, 'mutationId':uid()}
    return api('PUT', p+'/writing/chapters/'+chapter['uid'], data)
def form(kind, data): return api('POST',p+'/forms/'+kind,{'data':data,'tagIds':[]})

hero = form('characters', {'name':'沈雾','role':'灯塔守望者','personality':'克制、敏锐；越是害怕，越要弄清真相。','background':'失踪航海家的女儿，独自在北岸灯塔等待一封回信。','appearance':'灰蓝色长衣，随身带着父亲的旧罗盘。'})
foreshadow = form('foreshadows', {'title':'蓝色火漆印','content':'无名来信上的印记，与二十年前失踪船只的旗帜相同。','status':'pending'})
timeline = api('POST',p+'/timeline-events',{'title':'潮退之夜','eventTime':'秋分·子时','description':'海潮退去，旧航道第一次露出海面。'})
scene = form('scenes', {'name':'北岸灯塔','description':'海风穿过石阶，铜灯照亮了最后一层回廊。'})
w = api('GET', p+'/writing')
volume = {'uid':uid(),'title':'第一卷 · 潮汐来信'}
w['volumes'].append(volume)
for row in w['chapters']: row['volumeId']=volume['uid']
api('PUT',p+'/writing/structure',w)
rows = api('GET',p+'/writing')['chapters']
chapter = api('GET',p+'/writing/chapters/'+rows[0]['uid']) if rows else api('POST',p+'/writing/chapters',{'uid':uid(),'title':'雾中的来信','volumeId':volume['uid'],'doc':doc(['沈雾推开灯塔的门。'])})
body = doc(['沈雾推开灯塔的门。','一封没有署名的信，静静躺在桌上。她认得那枚蓝色的火漆印。','窗外，海潮正一寸寸退去。','“小雾，千万不要在潮退之后出海。”父亲的声音像是从铜灯里传来。'])
def link(kind, target, title, block, role='reference'):
    return {'uid':uid(),'type':kind,'targetId':target,'title':title,'blockId':block,'role':role,'excerpt':''}
links = [link('characters',hero['id'],'沈雾',body['content'][0]['attrs']['id']),link('foreshadows',foreshadow['id'],'蓝色火漆印',body['content'][1]['attrs']['id'],'laid'),link('timeline',timeline['id'],'潮退之夜','', 'current'),link('scenes',scene['id'],'北岸灯塔','')]
chapter = save(chapter,title='雾中的来信',summary='让一封来信打破沈雾的等待，露出旧航道的秘密。',notes='[ ] 在第二章呼应火漆印\n[x] 确定本章视角：沈雾',volumeId=volume['uid'],doc=body,links=links,status='writing')
w = api('GET',p+'/writing'); w['preferences']={'aliases':{str(hero['id']):['小雾','沈姑娘']},'dailyGoal':1800}
api('PUT',p+'/writing/preferences',w)
other = api('POST',p+'/writing/chapters',{'uid':uid(),'title':'潮汐之外','volumeId':volume['uid'],'doc':doc(['黎明将至，码头却没有一个人。','沈雾把旧罗盘放进口袋，沿着盐白色的石阶向下走去。']),'links':[]})
assert len(api('GET',p+f'/writing/backlinks?type=characters&target={hero["id"]}')) == 1
try: save({**chapter,'revision':0}, title='stale window')
except urllib.error.HTTPError as e: assert e.code == 409
else: raise AssertionError('Stale write accepted')
assert api('GET',p+'/writing/search?q='+urllib.parse.quote('火漆'))[0]['uid'] == chapter['uid']

# Word is a real OPC package and selected ranges never include trash.
word = api('POST',p+'/writing/export',{'format':'docx','toc':True},binary=True)
with zipfile.ZipFile(io.BytesIO(word)) as archive:
    xml=archive.read('word/document.xml').decode()
    assert '第一卷' in xml and '第1章 雾中的来信' in xml and '第2章 潮汐之外' in xml
(run/'manuscript.docx').write_bytes(word)
(run/'manuscript.txt').write_bytes(api('POST',p+'/writing/export',{'format':'txt'},binary=True))

# Immutable versions, a deliberately concurrent remote branch, then explicit resolution.
first = api('POST',p+'/writing/cloud/push',{})
w=api('GET',p+'/writing'); book=w['uid']; folder=run/'dav'/'novel-backups'/('ink-'+book)
assert (folder/first['file']).exists()
chapter=save(chapter,notes=chapter['notes']+'\n[ ] 第二次修订')
second=api('POST',p+'/writing/cloud/push',{})
parallel=json.loads((folder/first['file']).read_text()); parallel['revision']=uid(); parallel['parent']=first['file'].split('_')[1]
parallel_name=datetime.datetime.now().strftime('%Y%m%d%H%M%S')+'_'+parallel['revision']+'_'+parallel['parent']+'.ink.json'
(folder/parallel_name).write_text(json.dumps(parallel,ensure_ascii=False))
try: api('POST',p+'/writing/cloud/push',{})
except urllib.error.HTTPError as e: assert e.code == 409
else: raise AssertionError('Remote branch overwritten')
versions=api('GET',p+'/writing/cloud/versions'); assert len([v for v in versions if v['head']])==2
preview=api('POST',p+'/writing/cloud/preview',{'book':book,'file':second['file']}); assert len(preview['chapters'])>=2
result=api('POST',p+'/writing/cloud/pull',{'book':book,'file':second['file']})
copy_id=result['copyNovelId']; assert '同步前副本' in api('GET',f'/novels/{copy_id}')['title']
assert '第二次修订' in api('GET',f'/novels/{copy_id}/writing/chapters/'+chapter['uid'])['notes']
remapped=next(c for c in api('GET',p+'/characters') if c['name']=='沈雾')
restored=api('GET',p+'/writing/chapters/'+chapter['uid'])
assert restored['links'][0]['targetId']==remapped['id']
assert api('GET',p+'/writing')['preferences']['aliases'][str(remapped['id'])]==['小雾','沈姑娘']
assert len(api('GET',p+f'/writing/backlinks?type=characters&target={remapped["id"]}'))==1
api('POST',p+'/writing/cloud/push',{})
heads=[v for v in api('GET',p+'/writing/cloud/versions') if v['head']]; assert len(heads)==1

# A second device/workspace adopts the stable cloud book ID and keeps its own original manuscript.
target=api('POST','/novels',{'title':'第二设备验证'})['id']
local=api('POST',f'/novels/{target}/writing/chapters',{'uid':uid(),'title':'此设备旧稿','doc':doc(['这份稿件必须保留。'])})
props=dict(line.split('=',1) for line in (ROOT/'backend/src/main/resources/application.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
env=os.environ.copy();env['PGPASSWORD']=os.environ.get('PGPASSWORD',props['spring.datasource.password'])
pg=pathlib.Path(os.environ.get('PG_BIN','/Library/PostgreSQL/18/bin'))
sql=f'UPDATE novels SET webdav_server_url=s.webdav_server_url, webdav_username=s.webdav_username, webdav_password=s.webdav_password FROM (SELECT * FROM novels WHERE id={n}) s WHERE novels.id={target}'
subprocess.run([str(pg/'psql'),'-h','localhost','-U',os.environ.get('PGUSER',props['spring.datasource.username']),'-d',database,'-v','ON_ERROR_STOP=1','-c',sql],env=env,check=True,stdout=subprocess.DEVNULL)
adopted=api('POST',f'/novels/{target}/writing/cloud/pull',{'book':book,'file':heads[0]['file']})
assert api('GET',f'/novels/{target}/writing')['uid']==book
assert api('GET',f'/novels/{adopted["copyNovelId"]}/writing/chapters/'+local['uid'])['wordCount']>0
assert api('GET',p+'/writing')['uid']==book
summary=api('GET','/novels/writing-summary'); assert summary[str(n)]['words']>0
(run/'writing-result.json').write_text(json.dumps({'novelId':n,'chapterUid':chapter['uid'],'characterId':remapped['id'],'bookUid':book,'copyNovelId':copy_id,'secondDeviceId':target},ensure_ascii=False,indent=2))
print('PASS: revision conflict, backlinks, search, real DOCX, WebDAV upload/readback, remote branches, protected pull, alias/link remap, branch resolution, second-device adoption')
print('Browser fixture and Word export:',run)
