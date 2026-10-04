#!/usr/bin/env python3
"""Disposable PostgreSQL + real WsgiDAV regression check. Never uses the user's novel database.
Requires PG tools on PATH (or PG_BIN), Java 17+, Maven (MVN), WsgiDAV and cheroot in DAV_PYTHON.
Use --keep for a browser verification session, then --cleanup <run.json>.
"""
import base64, concurrent.futures, json, os, pathlib, secrets, signal, socket, subprocess, sys, tempfile, time, urllib.request, uuid
ROOT = pathlib.Path(__file__).resolve().parents[1]
PG = pathlib.Path(os.environ.get('PG_BIN', '/Library/PostgreSQL/18/bin'))
MVN = os.environ.get('MVN', 'mvn')
DAV_PYTHON = os.environ.get('DAV_PYTHON', sys.executable)
props = dict(line.split('=', 1) for line in (ROOT/'backend/src/main/resources/application.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
env = os.environ.copy()
env['PGPASSWORD'] = os.environ.get('PGPASSWORD', props['spring.datasource.password'])
USER = os.environ.get('PGUSER', props['spring.datasource.username'])
PGPORT = int(os.environ.get('PGPORT', '5432'))
if not 1 <= PGPORT <= 65535: raise ValueError('Invalid PostgreSQL port')
def sql(database, command):
    return subprocess.check_output([str(PG/'psql'), '-h', 'localhost', '-U', USER, '-d', database, '-v', 'ON_ERROR_STOP=1', '-Atc', command], env=env, text=True).strip()
def stop(pid):
    try:
        if os.getpgid(pid) == pid: os.killpg(pid, signal.SIGTERM)
    except ProcessLookupError: pass

def cleanup(state):
    for pid in state.get('pids', []): stop(pid)
    time.sleep(1)
    name = state['database']
    if not name.startswith('novel_it_') or not name.replace('_','').isalnum(): raise ValueError('Invalid test database')
    sql('postgres', f'DROP DATABASE IF EXISTS "{name}" WITH (FORCE)')
if len(sys.argv) > 2 and sys.argv[1] == '--cleanup':
    cleanup(json.loads(pathlib.Path(sys.argv[2]).read_text())); print('Integration services stopped; disposable database removed'); sys.exit(0)
run = pathlib.Path(tempfile.mkdtemp(prefix='novel-integration-'))
name = 'novel_it_' + uuid.uuid4().hex[:12]
legacy_title = 'Legacy integration ' + name
state = {'database': name, 'pids': [], 'directory': str(run)}
(run/'run.json').write_text(json.dumps(state))
port = int(os.environ.get('IT_PORT', '8081')); dav_port = int(os.environ.get('DAV_PORT', '8099'))
base = f'http://127.0.0.1:{port}/api'
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def api(method, path, data=None, headers=None):
    raw = json.dumps(data).encode() if data is not None else None
    request = urllib.request.Request(base+path, data=raw, method=method, headers={'Content-Type':'application/json', **(headers or {})})
    with http.open(request, timeout=30) as response: return json.load(response)
def launch(command, process_env, logfile, cwd=ROOT/'backend'):
    with logfile.open('w') as log:
        proc = subprocess.Popen(command, cwd=cwd, env=process_env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    state['pids'].append(proc.pid); (run/'run.json').write_text(json.dumps(state)); return proc

def backend(process_env):
    p = launch([MVN, '-B', 'spring-boot:run'], process_env, run/'backend.log')
    for _ in range(120):
        if p.poll() is not None: raise RuntimeError('Backend failed; see '+str(run/'backend.log'))
        try:
            novels = api('GET','/novels')
            if any(novel['title'] == legacy_title for novel in novels): return p
        except Exception: time.sleep(.5)
    raise TimeoutError('Backend startup timed out')
try:
    for check_port in [port, dav_port]:
        with socket.socket() as probe: probe.bind(('127.0.0.1', check_port))
    sql('postgres', f'CREATE DATABASE "{name}"')
    for file in ['schema.sql','migration_v3.sql']:
        subprocess.run([str(PG/'psql'), '-h','localhost','-U',USER,'-d',name,'-v','ON_ERROR_STOP=1','-f',str(ROOT/'database'/file)], env=env, check=True, stdout=subprocess.DEVNULL)
    # Legacy plaintext + VARCHAR credentials exercise automatic schema and data migration.
    sql(name, f"INSERT INTO novels(title,webdav_password) VALUES ('{legacy_title}',repeat('x',490))")
    dav_password = secrets.token_urlsafe(24)
    (run/'dav').mkdir()
    dav_env = env.copy(); dav_env.update(DAV_ROOT=str(run/'dav'), DAV_TEST_PASSWORD=dav_password, DAV_PORT=str(dav_port))
    dav_code = '''import os
from wsgidav.wsgidav_app import WsgiDAVApp
from cheroot.wsgi import Server
app=WsgiDAVApp({'provider_mapping': {'/': os.environ['DAV_ROOT']}, 'verbose':0,
'http_authenticator': {'accept_basic':True,'accept_digest':False,'default_to_digest':False},
'simple_dc': {'user_mapping': {'*': {'writer': {'password':os.environ['DAV_TEST_PASSWORD'],'roles':[]}}}}})
Server(('127.0.0.1',int(os.environ['DAV_PORT'])),app).start()
'''
    launch([DAV_PYTHON,'-c',dav_code], dav_env, run/'dav.log')
    app_env = env.copy(); app_env.update(SPRING_DATASOURCE_URL=f'jdbc:postgresql://localhost:{PGPORT}/{name}', SPRING_DATASOURCE_USERNAME=USER,
        SPRING_DATASOURCE_PASSWORD=env['PGPASSWORD'], SERVER_PORT=str(port), SERVER_ADDRESS='127.0.0.1', SPRING_JPA_SHOW_SQL='false',
        APP_UPLOAD_DIR=str(run/'uploads'), NOVEL_CREDENTIAL_KEY_FILE=str(run/'old.key'))
    app_env.pop('NOVEL_CREDENTIAL_KEY',None)
    server=backend(app_env)
    assert sql(name,f"SELECT webdav_password LIKE 'enc:v1:%' FROM novels WHERE title='{legacy_title}'") == 't'
    assert sql(name,"SELECT data_type FROM information_schema.columns WHERE table_name='novels' AND column_name='webdav_password'") == 'text'
    n=api('POST','/novels',{'title':'持续优化验证','description':'独立测试数据库'})['id']; prefix=f'/novels/{n}'
    tag=api('POST',prefix+'/tags',{'name':'验证标签'})['id']
    token=str(uuid.uuid4()); body={'data':{'name':'林舟','role':'测试人物'},'tagIds':[tag]}
    def submit():return api('POST',prefix+'/forms/characters',body,{'Idempotency-Key':token})
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool: ids=list(pool.map(lambda _:submit()['id'],range(2)))
    assert ids[0]==ids[1] and len(api('GET',prefix+'/characters'))==1
    api('POST',prefix+'/forms/worldview',{'data':{'name':'月门法则','category':'history','content':'事务保存验证'},'tagIds':[tag],'characterIds':[ids[0]],'sceneIds':[],'mapLocationIds':[]},{'Idempotency-Key':str(uuid.uuid4())})
    try: api('PUT',prefix+f'/forms/characters/{ids[0]}',{'data':{'name':'Should roll back'},'tagIds':[99999999]})
    except urllib.error.HTTPError as error: assert error.code==404
    else: raise AssertionError('Invalid association was accepted')
    assert api('GET',prefix+'/characters')[0]['name']=='林舟'
    png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a7OQAAAAASUVORK5CYII=')
    boundary='novel-it-'+uuid.uuid4().hex
    multipart=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="pixel.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+png+f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="imageType"\r\n\r\ncharacter_portrait\r\n--{boundary}--\r\n'.encode())
    with http.open(urllib.request.Request(base+prefix+'/images',data=multipart,headers={'Content-Type':'multipart/form-data; boundary='+boundary})) as r: image=json.load(r)
    api('PUT',prefix+f'/forms/characters/{ids[0]}',{'data':{'name':'林舟','portraitImage':f'/api{prefix}/images/{image["id"]}/file'},'tagIds':[tag]})
    api('POST',prefix+'/webdav/config',{'serverUrl':f'http://127.0.0.1:{dav_port}/','username':'writer','password':dav_password})
    upload=api('POST',prefix+'/webdav/sync/upload'); assert upload['success'], upload.get('message')
    files=api('GET',prefix+'/webdav/files'); assert any(f['name']==upload['filename'] for f in files)
    api('PUT',prefix+f'/forms/characters/{ids[0]}',{'data':{'name':'Changed'},'tagIds':[]})
    restored=api('POST',prefix+'/webdav/sync/download',{'filename':upload['filename']}); assert restored['success'],restored.get('message')
    character=api('GET',prefix+'/characters')[0]; assert character['name']=='林舟' and len(character['tags'])==1
    with http.open(f'http://127.0.0.1:{port}'+character['portraitImage']) as r: assert r.read()==png
    bundle=api('GET',prefix+'/backup'); target=api('POST','/novels',{'title':'Cross-novel restore'})['id']
    api('POST',f'/novels/{target}/backup',bundle)
    assert len(api('GET',f'/novels/{target}/characters'))==1
    stop(server.pid); server.wait(timeout=20)
    rotate=launch([MVN,'-B','spring-boot:run',f'-Dspring-boot.run.arguments=--maintenance.operation=rotate-key --maintenance.new-key-file={run}/next.key'],app_env,run/'rotation.log')
    assert rotate.wait(timeout=90)==0, 'Rotation command failed'
    app_env['NOVEL_CREDENTIAL_KEY_FILE']=str(run/'next.key');server=backend(app_env)
    assert api('POST',prefix+'/webdav/sync/upload')['success']
    print('PASS: PostgreSQL legacy upgrade, concurrent idempotency, rollback, images, real WebDAV upload/list/restore, cross-novel backup, key rotation/restart')
    print('Test novel:',n,'Run state:',run/'run.json')
    if '--keep' not in sys.argv: cleanup(state)
except BaseException:
    cleanup(state); raise
