"""Generate contract projections. Edit openapi.yaml, never the projections.
Planning source files: requirements.json/checks.json/decisions.json/prerequisites.json.
Usage: python generate_artifacts.py [--check]
"""
from pathlib import Path
import argparse, csv, io, json, re
import yaml
ROOT=Path(__file__).resolve().parent

def dump(x): return json.dumps(x,ensure_ascii=False,indent=2)+'\n'
def csvtext(rows,fields):
    f=io.StringIO(); w=csv.DictWriter(f,fieldnames=fields,lineterminator='\n'); w.writeheader();w.writerows(rows);return '\ufeff'+f.getvalue()
def projections():
    s=yaml.safe_load((ROOT/'openapi.yaml').read_text()); S=s['components']['schemas'];tables=s['x-database']['tables']
    out={'openapi.json':dump(s),'data_dictionary.json':dump(tables)}
    ops=[]
    for path,item in s['paths'].items():
      for method,o in item.items():
        if method not in ['get','post','patch','put','delete','head','options']:continue
        ops.append(dict(method=method.upper(),path='/api/v1'+path,id=o['operationId'],title=o['summary'],request=o.get('x-request-schema'),response=o.get('x-result-schema'),roles=','.join(o['x-required-roles']),requirement=o['x-requirement-id'],version=o['x-version-target'],paged=o.get('x-paged',False)))
    out['api_operations.json']=dump(ops)
    out['api_operations.csv']=csvtext(ops,list(ops[0]))
    state={n:S[n].get('enum',[S[n].get('const')]) for n in s['x-state-catalog']};out['states.json']=dump(state)
    trans=s['x-state-transitions'];out['transitions.json']=dump(trans);out['transitions.csv']=csvtext(trans,list(trans[0]))
    examples={n:x['examples'][0] for n,x in S.items() if x.get('examples')};out['examples.json']=dump(examples)
    sql=['-- GENERATED FROM openapi.yaml / x-database. Do not edit.','-- V1.2 isolated SIMULATION schema only. NOT a migration for existing Yudao.','-- Candidate target MySQL 8.4; enforce CHECK (MySQL >=8.0.16).','-- Execute in a disposable, explicitly selected empty database. No CREATE DATABASE or DROP.','SET NAMES utf8mb4;','']
    q=lambda x:'`'+x.strip()+'`'
    cols=lambda x:', '.join(q(c) for c in x.split(','))
    for t in tables:
      lines=[]
      for name,typ,constraint,desc in t['fields']:
        # ENUM declarations are projected from canonical schema enum, never separately maintained.
        if name in t.get('enum_refs',{}):
          e=S[t['enum_refs'][name]];typ='ENUM('+','.join("'"+str(v)+"'" for v in e.get('enum',[e.get('const')]))+')'
        c=constraint.replace("PK AUTO_INCREMENT", "NOT NULL AUTO_INCREMENT")
        # original field includes primary key; otherwise ensure id primary key below.
        lines.append(f'  {q(name)} {typ} {c} COMMENT '+"'"+desc.replace("'","''")+"'")
      if not any('PRIMARY KEY' in f[2] for f in t['fields']):lines.append('  PRIMARY KEY (`id`)')
      for i,x in enumerate(t['unique'],1):lines.append(f'  UNIQUE KEY `uq_{t["name"]}_{i}` ({cols(x)})')
      for i,x in enumerate(t['indexes'],1):lines.append(f'  KEY `ix_{t["name"]}_{i}` ({cols(x)})')
      for i,x in enumerate(t['checks'],1):lines.append(f'  CONSTRAINT `ck_{t["name"]}_{i}` CHECK ({x})')
      sql += [f'CREATE TABLE {q(t["name"])} (',',\n'.join(lines),') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;','']
    sql+=['-- FKs are added only after every referenced table exists.']
    for t in tables:
      for i,(local,target,foreign) in enumerate(t['fks'],1):sql.append(f'ALTER TABLE {q(t["name"])} ADD CONSTRAINT `fk_{t["name"]}_{i}` FOREIGN KEY ({cols(local)}) REFERENCES {q(target)} ({cols(foreign)}) ON DELETE RESTRICT ON UPDATE RESTRICT;')
    sql+=['','-- Application invariants: balance across journal lines, authorization and close/post locks','-- cannot be enforced by row CHECK alone. See chapters 3/4 and acceptance tests.']
    out['schema_simulation.sql']='\n'.join(sql)+'\n'
    req=json.loads((ROOT/'requirements.json').read_text()); ck=json.loads((ROOT/'checks.json').read_text())
    traces=[]
    for r in req:
      associated=[o for o in ops if o['requirement']==r[0]]
      traces.append(dict(requirement=r[0],title=r[1],design_section=r[4],target_version=r[3],operations=';'.join(o['id'] for o in associated) or 'DOCUMENT_OR_CROSS_CUTTING',checks=';'.join(c['id'] for c in ck if c['requirement']==r[0]),design_detail='3.13/4.4/4.7' if r[0]=='R-041' else '2.3/6.4/6.5' if r[0]=='R-042' else r[4],acceptance_status='NOT_RUN'))
    out['traceability.csv']=csvtext(traces,list(traces[0]))
    fields=['id','version','substage','requirement','item','expected','status','blocked','block_reason','mandatory','na_reason','na_approved_by','owner','acceptance_by','accepted_at','build_sha','evidence']
    out['version_checklist.csv']=csvtext([{k:c.get(k,'') for k in fields} for c in ck],fields)
    for n in ['decisions','prerequisites']:
      data=json.loads((ROOT/(n+'.json')).read_text());fields=list(dict.fromkeys(k for d in data for k in d)); rows=[]
      for d in data:rows.append({k:json.dumps(d.get(k),ensure_ascii=False) if isinstance(d.get(k),(list,dict)) else d.get(k,'') for k in fields})
      out[n+'.csv']=csvtext(rows,fields)
    # A Mermaid file for each domain avoids mixing unrelated status machines.
    md=['# V1.2 generated state diagrams','', 'Transition permissions/guards/side effects are authoritative in openapi.yaml x-state-transitions.','']
    for n,values in state.items():
      if not n.endswith('Status'):continue
      md+=['## '+n,'','```mermaid','stateDiagram-v2']
      md += ['    state "'+v+'" as '+v for v in values]
      # explicitly only independently actionable edges; complete transitions table has coupled operations
      edges={
      'OrderStatus':[('DRAFT','PUBLISHED'),('PUBLISHED','CONFIRMED'),('PUBLISHED','CANCELLED'),('PUBLISHED','EXPIRED'),('CONFIRMED','FULFILLED'),('CONFIRMED','CANCELLED'),('CONFIRMED','EXPIRED')],
      'BusinessDayStatus':[('OPEN','CLOSING'),('CLOSING','CLOSED'),('CLOSING','OPEN'),('CLOSED','REOPENED_REVIEW'),('REOPENED_REVIEW','CLOSED')],
      'BusinessDayRequestStatus':[('REQUESTED','APPROVED'),('REQUESTED','REJECTED'),('APPROVED','EXECUTING'),('EXECUTING','EXECUTED'),('EXECUTING','FAILED'),('APPROVED','EXPIRED')],
      'RepaymentStatus':[('CREATED','PENDING'),('PENDING','CONFIRMED'),('PENDING','FAILED'),('PENDING','CLOSED'),('CLOSED','EXCEPTION'),('FAILED','EXCEPTION'),('EXCEPTION','CONFIRMED')],
      'ReceivableStatus':[('OPEN','PARTIAL'),('OPEN','SETTLED'),('PARTIAL','SETTLED')],
      'RefundStatus':[('REQUESTED','APPROVED'),('REQUESTED','REJECTED'),('APPROVED','PROCESSING'),('APPROVED','SUCCEEDED'),('PROCESSING','SUCCEEDED'),('PROCESSING','FAILED'),('PROCESSING','EXCEPTION'),('FAILED','PROCESSING'),('EXCEPTION','PROCESSING')],
      'ReservationStatus':[('HELD','CONSUMED'),('HELD','RELEASED'),('HELD','EXPIRED')],
      'DisputeStatus':[('OPEN','REVIEWING'),('REVIEWING','RESOLVED'),('RESOLVED','APPEALED'),('APPEALED','REVIEWING'),('RESOLVED','CLOSED')],
      'ReconciliationStatus':[('CREATED','RUNNING'),('RUNNING','MATCHED'),('RUNNING','DIFFERENCE'),('RUNNING','FAILED'),('DIFFERENCE','RESOLVED')],
      'AdjustmentStatus':[('REQUESTED','APPROVED'),('REQUESTED','REJECTED'),('APPROVED','POSTED')],
      'FulfillmentStatus':[('NOT_READY','READY'),('READY','FULFILLED'),('READY','VOID')],
      }.get(n,[])
      for a,b in edges:md.append(f'    {a} --> {b}')
      md+=['```','']
    out['state_machines.md']='\n'.join(md).rstrip()+'\n'
    return out

def main():
    p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');a=p.parse_args();bad=[]
    for name,text in projections().items():
      if a.check:
        if not (ROOT/name).exists() or (ROOT/name).read_text()!=text:bad.append(name)
      else:(ROOT/name).write_text(text,encoding='utf-8')
    if bad:raise SystemExit('Generated files drift: '+', '.join(bad))
    print('projection drift check passed' if a.check else 'generated projections')
if __name__=='__main__': main()
