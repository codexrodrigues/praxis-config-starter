import pathlib,subprocess,os,json,hashlib,datetime,shutil,sys,xml.etree.ElementTree as E
w=pathlib.Path('${CONFIG_SOURCE}')
name=sys.argv[1];selection=sys.argv[2]
out=pathlib.Path('/tmp/praxis-page-p3b-512-evidence')/name
out.mkdir(exist_ok=False)
def git(*args):return subprocess.check_output(['git',*args],cwd=w,text=True).strip()
assert not git('status','--porcelain')
sha=git('rev-parse','HEAD');tree=git('rev-parse','HEAD^{tree}')
command=['${MAVEN_HOME}/bin/mvn','-B','-Dmaven.repo.local=${P3B_M2}','-Dpraxis.page.p3b.neon.it=true','-DfailIfNoTests=true','-Dtest='+selection,'test']
env=os.environ.copy();env['PRAXIS_PAGE_P3B_NEON_RUNTIME_FILE']='${PRIVATE_ASSIGNMENT}/runtime.json'
report=w/'target/surefire-reports/TEST-org.praxisplatform.config.service.PageRuntimePostgresIT.xml'
if report.exists():report.unlink()
manifest={'sourceCommit':sha,'sourceTree':tree,'productionBase':'d7b572ad369f24f74f3aeb59e47bbf540df2f24d','command':command,'startUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'sourceFiles':{},'scope':'Assigned P3-B runtime only; no packaged artifact claimed.'}
for f in git('diff','--name-only','724f92ff','HEAD').splitlines():
 p=w/f
 if p.is_file():manifest['sourceFiles'][f]=hashlib.sha256(p.read_bytes()).hexdigest()
(out/'run-start.json').write_text(json.dumps(manifest,indent=2)+'\n')
with (out/'maven.log').open('wb') as log:result=subprocess.run(command,cwd=w,env=env,stdout=log,stderr=subprocess.STDOUT)
manifest['exitCode']=result.returncode;manifest['finishUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat();manifest['sourceUnchanged']=git('rev-parse','HEAD')==sha and not git('status','--porcelain')
manifest['files']={'maven.log':hashlib.sha256((out/'maven.log').read_bytes()).hexdigest()}
if report.exists():
 shutil.copy2(report,out/report.name);r=E.parse(report).getroot();manifest['suite']={key:r.attrib.get(key) for key in ['name','tests','failures','errors','skipped','time']};manifest['files'][report.name]=hashlib.sha256(report.read_bytes()).hexdigest()
else:manifest['missingXml']=True
(out/'run.json').write_text(json.dumps(manifest,indent=2)+'\n')
print(json.dumps({'run':name,'source':sha,'exit':result.returncode,'suite':manifest.get('suite'),'unchanged':manifest['sourceUnchanged']}))
sys.exit(result.returncode)
