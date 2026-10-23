import json,pathlib,secrets,urllib.parse,hashlib,sys
import psycopg,certifi
from psycopg import sql
root=pathlib.Path('${PRIVATE_ASSIGNMENT}')
evidence=pathlib.Path('/tmp/praxis-page-p3b-512-evidence')
url=json.loads((root/'owner.json').read_text())['url'];parts=urllib.parse.urlparse(url)
assert parts.hostname=='ep-floral-voice-a40bufyv.us-east-1.aws.neon.tech'
role='p3b_runtime_512_runtime';schema='p3b_runtime_512_20261023'
control=json.loads((evidence/'control-plane.json').read_text())
assert control['project']['permission']=='ADMIN' and control['branch']['id']=='br-weathered-mountain-a48o0map'
assert control['endpoint']['host']==parts.hostname and control['project']['pgVersion']==17
manifest={'projectId':control['project']['id'],'branchId':control['branch']['id'],'endpoint':parts.hostname,'schema':schema,'runtime':role,'tlsMode':'verify-full','caBundleSha256':hashlib.sha256(pathlib.Path(certifi.where()).read_bytes()).hexdigest(),'migrations':[]}
password=secrets.token_urlsafe(36)
try:
 with psycopg.connect(url,sslmode='verify-full',sslrootcert=certifi.where(),connect_timeout=20) as conn:
  info=conn.execute("SELECT current_user,current_database(),current_setting('server_version'),rolsuper,rolcreatedb,rolcreaterole,rolbypassrls FROM pg_roles WHERE rolname=current_user").fetchone()
  tls=conn.execute('SELECT ssl,version,cipher,bits FROM pg_stat_ssl WHERE pid=pg_backend_pid()').fetchone()
  assert info[0]=='neondb_owner' and info[1]=='neondb' and info[2].startswith('17.') and not info[3]
  assert conn.pgconn.ssl_in_use
  assert json.loads((evidence/'tls-peer.json').read_text())['verifyHostname']
  manifest['ownerClientSsl']=True
  manifest['backendTlsNote']='pg_stat_ssl describes backend link; it is not the client TLS gate.'
  manifest['ownerIdentity']=list(info);manifest['ownerTls']=list(tls)
  print('Owner preflight (before mutation):',json.dumps({'endpoint':parts.hostname,'identity':info,'tls':tls,'verifyFull':True}),flush=True)
  assert conn.execute('SELECT count(*) FROM pg_namespace WHERE nspname=%s',(schema,)).fetchone()[0]==0
  assert conn.execute('SELECT count(*) FROM pg_roles WHERE rolname=%s',(role,)).fetchone()[0]==0
  conn.execute(sql.SQL('CREATE SCHEMA {}').format(sql.Identifier(schema)))
  conn.execute(sql.SQL('CREATE ROLE {} LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD {}').format(sql.Identifier(role),sql.Literal(password)))
  conn.execute(sql.SQL('SET LOCAL search_path TO {}').format(sql.Identifier(schema)))
  for version in [64,65,66,67,68,70]:
   files=list(pathlib.Path('src/main/resources/db/migration').glob(f'V{version}__*.sql'));assert len(files)==1
   data=files[0].read_bytes();conn.execute(data.decode(),prepare=False)
   manifest['migrations'].append({'file':str(files[0]),'sha256':hashlib.sha256(data).hexdigest()});print('Applied',files[0].name,flush=True)
  conn.execute(sql.SQL('GRANT USAGE ON SCHEMA {} TO {}').format(sql.Identifier(schema),sql.Identifier(role)))
  conn.execute(sql.SQL('GRANT SELECT,INSERT ON ALL TABLES IN SCHEMA {} TO {}').format(sql.Identifier(schema),sql.Identifier(role)))
  conn.execute(sql.SQL('GRANT UPDATE ON ui_layout_draft,ui_layout_release_review,ui_layout_release_head TO {}').format(sql.Identifier(role)))
  conn.execute(sql.SQL('GRANT UPDATE(id) ON ui_layout_definition TO {}').format(sql.Identifier(role)))
 assignment={'projectId':control['project']['id'],'branchId':control['branch']['id'],'endpoint':parts.hostname,'schema':schema,'username':role,'password':password,'jdbcUrl':f'jdbc:postgresql://{parts.hostname}/neondb?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory'}
 p=root/'runtime.json';p.write_text(json.dumps(assignment));p.chmod(0o600)
 with psycopg.connect(host=parts.hostname,dbname='neondb',user=role,password=password,sslmode='verify-full',sslrootcert=certifi.where(),connect_timeout=20) as conn:
  conn.execute(sql.SQL('SET search_path TO {}').format(sql.Identifier(schema)))
  info=conn.execute("SELECT current_user,current_schema(),rolsuper,rolcreatedb,rolcreaterole,rolbypassrls,has_schema_privilege(current_user,current_schema(),'CREATE'),has_table_privilege(current_user,'ui_page_creation_evidence','UPDATE'),has_table_privilege(current_user,'ui_page_catalog','DELETE'),has_table_privilege(current_user,'ui_layout_release_head','UPDATE') FROM pg_roles WHERE rolname=current_user").fetchone()
  tls=conn.execute('SELECT ssl,version,cipher,bits FROM pg_stat_ssl WHERE pid=pg_backend_pid()').fetchone()
  assert info[:2]==(role,schema) and not any(info[2:9]) and info[9] and conn.pgconn.ssl_in_use
  manifest['runtimeIdentity']=list(info);manifest['runtimeTls']=list(tls);manifest['runtimeClientSsl']=True
  manifest['tableCount']=conn.execute('SELECT count(*) FROM information_schema.tables WHERE table_schema=%s',(schema,)).fetchone()[0]
  manifest['runtimeGrants']=conn.execute("SELECT table_name,privilege_type FROM information_schema.role_table_grants WHERE table_schema=%s AND grantee=current_user ORDER BY table_name,privilege_type",(schema,)).fetchall()
  print('Runtime preflight:',json.dumps({'endpoint':parts.hostname,'identity':info,'tls':tls,'verifyFull':True}),flush=True)
 manifest['prepareScriptSha256']=hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest()
 (evidence/'neon-preparation.json').write_text(json.dumps(manifest,indent=2)+'\n')
 print('Assigned schema prepared; all owner/runtime connections closed. No campaign executed.',flush=True)
except Exception as error:
 print(json.dumps({'preparationFailed':type(error).__name__,'sqlstate':getattr(error,'sqlstate',None),'details':'suppressed to protect credentials'}),flush=True)
 sys.exit(1)
