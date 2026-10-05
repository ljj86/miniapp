"""V1.2 reproducible static checks. NOT a business acceptance or MySQL execution.
Requires: PyYAML, jsonschema. Optional full OAS validator: openapi-spec-validator.
python validate_artifacts.py [--require-external]
Exit nonzero for any implemented check failure; an optional validator SKIP is explicitly reported.
"""
from pathlib import Path
import argparse,copy,csv,hashlib,importlib.util,json,re,sqlite3,subprocess,sys
import yaml,jsonschema
from referencing import Registry,Resource
from referencing.jsonschema import DRAFT202012
from generate_artifacts import projections
from mock_signature import canonical,sign,verify
ROOT=Path(__file__).resolve().parent; QA=ROOT.parent/'qa'
class UniqueLoader(yaml.SafeLoader):pass

def unique_mapping(loader,node,deep=False):
    result={}
    for kn,vn in node.value:
        key=loader.construct_object(kn,deep=deep)
        if key in result:raise ValueError('duplicate YAML key: '+str(key))
        result[key]=loader.construct_object(vn,deep=deep)
    return result
UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,unique_mapping)

def walk(x):
    if isinstance(x,dict):
        yield x
        for v in x.values():yield from walk(v)
    elif isinstance(x,list):
        for v in x:yield from walk(v)

def resolve(spec,ref):
    if not ref.startswith('#/'):raise ValueError('remote reference not permitted in this package')
    v=spec
    for k in ref[2:].split('/'):v=v[k.replace('~1','/').replace('~0','~')]
    return v

def inspect(spec):
    issues=[];S=spec['components']['schemas'];ops=[]
    if spec.get('openapi')!='3.1.1':issues.append('OAS_VERSION')
    for x in walk(spec):
        if '$ref' in x:
            try:resolve(spec,x['$ref'])
            except (KeyError,ValueError):issues.append('UNRESOLVED_REF '+str(x['$ref']))
    for p,item in spec['paths'].items():
        if not p.startswith('/') or re.search(r'\s',p):issues.append('INVALID_PATH '+p)
        if re.search(r'/(deposits|settlements|withdrawals|credit/inquiries|api-packages)(/|$)',p):issues.append('FORBIDDEN_REAL_ROUTE '+p)
        for m,o in item.items():
            if m not in ['get','post','put','patch','delete','head','options']:continue
            ops.append(o);pars=o.get('parameters',[])
            if {a['name'] for a in pars if a.get('in')=='path' and a.get('required')}!=set(re.findall(r'\{([^}]+)\}',p)):issues.append('PATH_PARAM '+p)
            if o.get('x-idempotency-required') and not any(a.get('name')=='Idempotency-Key' and a.get('required') for a in pars):issues.append('IDEMPOTENCY '+p)
            if 'security' not in o or not o.get('x-required-roles'):issues.append('SECURITY '+p)
            if o.get('x-simulation-only') is not True:issues.append('ENV '+p)
    ids=[o['operationId'] for o in ops]
    if len(ids)!=len(set(ids)):issues.append('DUPLICATE_OPERATION')
    for n,pattern in [('UserUid','^y[0-9]{8}$'),('MerchantUid','^s[0-9]{7}$'),('Id','^[1-9][0-9]{0,19}$')]:
        if S.get(n,{}).get('pattern')!=pattern:issues.append('REGEX '+n)
    for x in walk(spec):
        if 'enum' in x and 'FULLFILLED' in x['enum']:issues.append('MISSPELLED_STATE')
    names={t['name']:t for t in spec['x-database']['tables']}
    for n,t in names.items():
        fs={f[0]:f for f in t['fields']}
        if len(fs)!=len(t['fields']):issues.append('DUPLICATE_FIELD '+n)
        for ix in t['indexes']+t['unique']:
            if not set(a.strip() for a in ix.split(','))<=set(fs):issues.append('INDEX '+n)
        for local,target,foreign in t['fks']:
            if target not in names:issues.append('FK_TARGET '+n);continue
            lf=[v.strip() for v in local.split(',')];rf=[v.strip() for v in foreign.split(',')]; tf={f[0]:f for f in names[target]['fields']}
            if len(lf)!=len(rf) or not set(lf)<=set(fs) or not set(rf)<=set(tf):issues.append('FK_COLUMNS '+n);continue
            for a,b in zip(lf,rf):
                if fs[a][1]!=tf[b][1]:issues.append('FK_TYPE '+n+'.'+a)
            uniq=[['id']]+[[v.strip() for v in q.split(',')] for q in names[target]['unique']]
            if rf not in uniq:issues.append('FK_NOT_UNIQUE '+n+' -> '+target)
        for f,en in t.get('enum_refs',{}).items():
            expected=S[en].get('enum',[S[en].get('const')]);actual=re.findall(r"'([^']+)'",fs[f][1])
            if actual!=expected:issues.append('ENUM_DRIFT '+n+'.'+f)
        if 'maker_id' in fs and 'checker_id' in fs:
            if 'checker_id IS NULL OR checker_id <> maker_id' not in t['checks']:issues.append('SELF_REVIEW_CHECK '+n)
    return issues

def main():
    p=argparse.ArgumentParser();p.add_argument('--require-external',action='store_true');args=p.parse_args()
    QA.mkdir(exist_ok=True); results=[]
    def record(name,ok,detail=''):results.append({'name':name,'status':'PASS' if ok else 'FAIL','detail':detail})
    spec=yaml.load((ROOT/'openapi.yaml').read_text(),Loader=UniqueLoader);S=spec['components']['schemas']
    record('YAML无重复键',True)
    issues=inspect(spec);record('路径/引用/幂等/身份/状态/DDL交叉静态检查',not issues,'; '.join(issues))
    record('唯一来源生成物无漂移',all((ROOT/n).read_text()==t for n,t in projections().items()))
    sql=(ROOT/'schema_simulation.sql').read_text()
    record('DDL不得残留字典PK缩写',not re.search(r'\bPK\s+AUTO_INCREMENT',sql) and sql.count('CREATE TABLE `')==38)
    registry=Registry().with_resource('urn:paylater:contract',Resource.from_contents(spec,default_specification=DRAFT202012))
    schema_errors=[];exerrors=[];examplecount=0
    for n,s in S.items():
        try:jsonschema.Draft202012Validator.check_schema(s)
        except Exception as e:schema_errors.append(n+': '+str(e))
        v=jsonschema.Draft202012Validator({'$ref':'urn:paylater:contract#/components/schemas/'+n},registry=registry,format_checker=jsonschema.FormatChecker())
        for ex in s.get('examples',[]):
            examplecount+=1
            try:exerrors += [n+': '+e.message for e in v.iter_errors(ex)]
            except Exception as e:exerrors.append(n+': '+str(e))
    record('JSON Schema 2020-12组件语法',not schema_errors,f'{len(S)}个组件；'+str(schema_errors))
    record('完整示例实际Schema验证',not exerrors,f'{examplecount}个组件样例；'+str(exerrors))
    for n,good,bad in [('UserUid',['y00000001'],['y1','y000000001','x00000001','y0000000a']),('MerchantUid',['s0000001'],['s00001','y0000001','s00000001']),('Id',['1','18446744073709551615'],['0','-1','01','1.1','100000000000000000000'])]:
        r=re.compile(S[n]['pattern']);record(n+'正负例',all(r.fullmatch(x) for x in good) and not any(r.fullmatch(x) for x in bad))
    record('BIGINT值域超界第二层示例',int('18446744073709551616')>int(S['Id']['x-integer-maximum']),'正则通过不等于数据库值域通过')
    # Negative mutation checks: each must cause our validator to fail.
    mutations=[('UID量词破坏',lambda x:x['components']['schemas']['UserUid'].update(pattern='^y[0-9][8]$')),
      ('未定义引用',lambda x:x['components']['schemas']['User'].update(properties={'uid':{'$ref':'#/components/schemas/Missing'}})),
      ('路径空格',lambda x:x['paths'].update({'/bad path':copy.deepcopy(next(iter(x['paths'].values())))})),
      ('错拼状态',lambda x:x['components']['schemas']['OrderStatus']['enum'].append('FULLFILLED')),
      ('复核比较反向',lambda x:next(t for t in x['x-database']['tables'] if t['name']=='sim_rule_version')['checks'].__setitem__(1,'checker_id IS NULL OR checker_id <= maker_id')),
      ('枚举漂移',lambda x:x['components']['schemas']['MerchantStatus']['enum'].append('SUBMITTED'))]
    for name,fn in mutations:
        mutant=copy.deepcopy(spec);fn(mutant);record('负例能拦截：'+name,bool(inspect(mutant)))
    # Execute only the SQL-standard boolean checks on SQLite. Not MySQL DDL.
    con=sqlite3.connect(':memory:');con.execute("CREATE TABLE review(maker_id INTEGER NOT NULL,checker_id INTEGER,status TEXT, CHECK(checker_id IS NULL OR checker_id <> maker_id),CHECK(status='DRAFT' OR checker_id IS NOT NULL))")
    cases=[(10,20,'APPROVED',True),(20,10,'APPROVED',True),(10,10,'APPROVED',False),(10,None,'APPROVED',False),(10,None,'DRAFT',True)]
    for maker,checker,status,expected in cases:
        ok=True
        try:con.execute('INSERT INTO review VALUES(?,?,?)',(maker,checker,status))
        except sqlite3.IntegrityError:ok=False
        record(f'CHECK真值 maker={maker}/checker={checker}/{status}',ok==expected,'SQLite只验证该布尔表达式，非MySQL迁移验收')
    ck=json.loads((ROOT/'checks.json').read_text());req=json.loads((ROOT/'requirements.json').read_text());rids={r[0] for r in req};cids={c['id'] for c in ck}
    record('150项唯一检查和42项需求',len(ck)==len(cids)==150 and len(rids)==42)
    record('无伪造验收通过',all(c['status']=='NOT_RUN' and not c['evidence'] for c in ck))
    record('检查需求引用全部存在',all(c['requirement'] in rids for c in ck))
    rows=list(csv.DictReader((ROOT/'traceability.csv').open(encoding='utf-8-sig')))
    record('追溯覆盖与检查编号',len(rows)==42 and all(r['checks'] and set(r['checks'].split(';'))<=cids for r in rows))
    record('0.2a/0.2b独立计数',sum(c['substage']=='0.2a' for c in ck)==13 and sum(c['substage']=='0.2b' for c in ck)==3)
    record('36状态转换编号连续', [x['id'] for x in spec['x-state-transitions']]==[f'SM-{i:02d}' for i in range(1,37)])
    dec=json.loads((ROOT/'decisions.json').read_text());pr=json.loads((ROOT/'prerequisites.json').read_text())
    record('DEC未伪造审批',len(dec)==13 and all(d['status']=='PENDING' and not d['approval_evidence'] for d in dec))
    record('DEC截止/未决动作/默认召集人齐全',all(d['deadline'] and d['allowed_when_pending'] and d['prohibited_when_pending'] and d['default_coordinator'] and d['unassigned_action'] for d in dec))
    record('PR未伪造申请通过',len(pr)==19 and all(d['status']=='NOT_VERIFIED' for d in pr))
    gate=json.loads((ROOT/'gate_plan.json').read_text())['nodes'];visiting=set();done=set()
    def dfs(n):
        if n in visiting: raise ValueError('cycle '+n)
        if n in done:return
        visiting.add(n)
        for dependency in gate.get(n,[]):dfs(dependency)
        visiting.remove(n);done.add(n)
    try:
        for n in gate:dfs(n)
        acyclic=True
    except ValueError:acyclic=False
    record('决策与阶段依赖无环',acyclic,'仅验证计划拓扑，不把待审批节点标通过')
    record('Mock分支不依赖0.2b',gate['0.3']==['0.2a','PR-05'] and '0.2b' not in gate['0.4'])
    record('完整1.0必须真机与最终授权',gate['1.0']==['0.2','0.9','PR-10'] and '0.2b' in gate['0.2'])
    record('0.4/0.5/0.6决策硬入口',{'DEC-11','DEC-13','RV-SIM-001'}<=set(gate['0.4']) and 'PR-06' in gate['0.5'] and 'PR-07' in gate['0.6'])

    # Language-independent golden bytes. The key is deliberately public TEST ONLY.
    key=b'TEST_ONLY_NOT_A_PRODUCTION_KEY_32_BYTES';ts='1790726400';nonce='SIM_nonce_00000001';body='{"environment":"SIMULATION","note":"测试"}'.encode()
    sig=sign(key,ts,nonce,body);blob=canonical(ts,nonce,body)
    record('签名使用LF字节',blob.count(b'\n')==3 and blob.endswith(b'\n'))
    record('HMAC正例',verify(key,ts,nonce,body,sig,now_seconds=int(ts)))
    record('HMAC拒绝改正文',not verify(key,ts,nonce,body+b' ',sig,now_seconds=int(ts)))
    record('HMAC拒绝过期',not verify(key,ts,nonce,body,sig,now_seconds=int(ts)+301))
    vector={'testOnly':True,'secretBase64':__import__('base64').b64encode(key).decode(),'timestamp':ts,'nonce':nonce,'bodyBase64':__import__('base64').b64encode(body).decode(),'canonicalHex':blob.hex(),'signatureBase64':sig}
    (ROOT/'mock_signature_vector.json').write_text(json.dumps(vector,ensure_ascii=False,indent=2)+'\n')
    js="""const fs=require('fs'),c=require('crypto'),v=JSON.parse(fs.readFileSync(process.argv[1]));const raw=Buffer.from(v.bodyBase64,'base64');const b=Buffer.concat([Buffer.from(v.timestamp+'\\n'+v.nonce+'\\n'),raw,Buffer.from('\\n')]);const h=c.createHmac('sha256',Buffer.from(v.secretBase64,'base64')).update(b).digest('base64');if(h!==v.signatureBase64||b.toString('hex')!==v.canonicalHex)process.exit(1);console.log('PASS');"""
    try:r=subprocess.run(['node','-e',js,str(ROOT/'mock_signature_vector.json')],capture_output=True,text=True,timeout=10);record('Python与Node签名字节一致',r.returncode==0,r.stdout+r.stderr)
    except Exception as e:record('Python与Node签名字节一致',False,str(e))
    ext={'status':'SKIP','reason':'openapi-spec-validator未安装；当前环境外网安装失败。已运行的结构检查不冒称完整OpenAPI标准校验。'}
    if importlib.util.find_spec('openapi_spec_validator'):
        try:
            from openapi_spec_validator import validate
            validate(spec);ext={'status':'PASS','reason':'openapi-spec-validator full specification validation'}
        except Exception as e:ext={'status':'FAIL','reason':str(e)}
    if args.require_external:record('要求外部完整OpenAPI校验器',ext['status']=='PASS',ext['reason'])
    elif ext['status']=='FAIL':record('外部完整OpenAPI校验',False,ext['reason'])
    report={'scope':'文档和契约静态验证、CHECK表达式和签名算法自测；不等于业务验收。','counts':{'operations':len(json.loads((ROOT/'api_operations.json').read_text())),'schemas':len(S),'tables':len(spec['x-database']['tables']),'requirements':len(req),'product_checklist':len(ck),'state_transitions':len(spec['x-state-transitions'])},'checks':results,'summary':{'passed':sum(r['status']=='PASS' for r in results),'failed':sum(r['status']=='FAIL' for r in results)},'external_openapi_validator':ext,'not_executed':['MySQL 8.4 DDL执行及现有芋道迁移','实际服务76接口联调和150项产品验收','k6在线性能测试、真机绑定、权限渗透、灾备恢复','业务日并发数据库事务测试'],'all_implemented_checks_passed':all(r['status']=='PASS' for r in results)}
    (QA/'static_validation.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(report['summary'],ensure_ascii=False));
    for r in results:
        if r['status']=='FAIL':print(r)
    if not report['all_implemented_checks_passed']:raise SystemExit(1)
if __name__=='__main__':main()
