#!/usr/bin/env python3
"""Validate an AI response contract and its exact local JSON evidence; read-only, stdlib only."""
import argparse
import json
import gzip
import hashlib
import pathlib
import re
from decimal import Decimal


class InvalidAnalysis(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise InvalidAnalysis(message)


def schema_check(value, schema, at='$'):
    kinds={'object':dict,'array':list,'string':str,'boolean':bool,'null':type(None),'integer':int,'number':(int,float,Decimal)}
    expected=schema.get('type')
    if expected:
        allowed=expected if isinstance(expected,list) else [expected]
        require(any(isinstance(value,kinds[k]) and not (k in ('integer','number') and isinstance(value,bool)) for k in allowed),f'{at}: invalid type')
    if 'const' in schema:
        require(same_json(value,schema['const']),f'{at}: invalid constant')
    if 'enum' in schema:
        require(value in schema['enum'],f'{at}: invalid enum value')
    if isinstance(value,(int,float,Decimal)) and not isinstance(value,bool):
        require('minimum' not in schema or value>=schema['minimum'],f'{at}: below minimum')
        require('maximum' not in schema or value<=schema['maximum'],f'{at}: above maximum')
    if isinstance(value,dict):
        require(all(k in value for k in schema.get('required',[])),f'{at}: missing required fields')
        props=schema.get('properties',{})
        if schema.get('additionalProperties') is False:
            require(set(value)<=set(props),f'{at}: unexpected fields')
        for k,v in value.items():
            if k in props:
                schema_check(v,props[k],at+'.'+k)
    if isinstance(value,list):
        require(len(value)>=schema.get('minItems',0),f'{at}: too few items')
        require(len(value)<=schema.get('maxItems',float('inf')),f'{at}: too many items')
        for i,v in enumerate(value):
            schema_check(v,schema.get('items',{}),f'{at}[{i}]')
    if isinstance(value,str):
        require(len(value)>=schema.get('minLength',0),f'{at}: empty text')
        if 'pattern' in schema:
            require(re.fullmatch(schema['pattern'],value) is not None,f'{at}: invalid pattern')


def pointer(document, path):
    if path=='':
        return document,[document]
    require(path.startswith('/') and re.search(r'~(?![01])',path) is None,'Invalid JSON Pointer')
    current=document
    ancestors=[document]
    for token in path[1:].split('/'):
        token=token.replace('~1','/').replace('~0','~')
        try:
            if isinstance(current,list):
                require(re.fullmatch(r'0|[1-9][0-9]*',token) is not None,'Invalid array index')
                current=current[int(token)]
            else:
                current=current[token]
        except (KeyError,IndexError,TypeError):
            raise InvalidAnalysis(f'Evidence pointer does not exist: {path}') from None
        ancestors.append(current)
    return current,ancestors


def same_json(actual,claimed):
    if isinstance(actual,bool) or isinstance(claimed,bool):
        return type(actual) is type(claimed) and actual==claimed
    if isinstance(actual,dict) and isinstance(claimed,dict):
        return actual.keys()==claimed.keys() and all(same_json(actual[k],claimed[k]) for k in actual)
    if isinstance(actual,list) and isinstance(claimed,list):
        return len(actual)==len(claimed) and all(same_json(a,b) for a,b in zip(actual,claimed))
    if isinstance(actual,(int,float,Decimal)) and isinstance(claimed,(int,float,Decimal)):
        return Decimal(str(actual))==Decimal(str(claimed))
    return type(actual) is type(claimed) and actual==claimed


def expected_unit(actual,pointer):
    token=pointer.rsplit('/',1)[-1].replace('~1','/').replace('~0','~').lower()
    if isinstance(actual,bool):
        return 'boolean'
    lower=pointer.lower()
    if '/latencybucketcounts/' in lower:return 'count'
    if '/latencybucketupperboundsnanos/' in lower:return 'nanoseconds'
    if token=='completedpersecond':return 'per_second'
    if token in ('errorrate','observederrorfraction'):return 'ratio'
    if token in ('completed','unknownstatus') or token.endswith('requests'):return 'count'
    if token=='processcputime':
        return 'nanoseconds'
    if token in ('processcpuload','cpuload') or token.endswith('ratio'):
        return 'ratio'
    lower=pointer.lower()
    if ('/heap/' in lower or '/nonheap/' in lower) and token in ('used','committed','max','init'):
        return 'bytes'
    if token.endswith('nanos'):
        return 'nanoseconds'
    if token.endswith('millis'):
        return 'milliseconds'
    if token.endswith('percent'):
        return 'percent'
    if token.endswith('bytes'):
        return 'bytes'
    if token.endswith('sizemb'):
        return 'mebibytes'
    numeric=isinstance(actual,(int,float,Decimal)) or (isinstance(actual,str) and re.fullmatch(r'-?[0-9]+',actual))
    if numeric and token.endswith(('spans','calls','errors','classes','methods','traces','collections','occurrences','subtrees','count','capacity','destinations')):
        return 'count'
    return 'text' if isinstance(actual,(str,dict,list)) else None


def nearest_identity(parents,kind):
    for parent in reversed(parents):
        if not isinstance(parent,dict):
            continue
        if kind=='session' and isinstance(parent.get('sessionId'),str):
            return parent['sessionId']
        if kind=='trace' and isinstance(parent.get('traceId'),str):
            return parent['traceId']
        if kind=='method':
            if isinstance(parent.get('method'),str):
                return parent['method']
            if isinstance(parent.get('rootMethod'),str):
                return parent['rootMethod']
            if isinstance(parent.get('className'),str) and isinstance(parent.get('methodName'),str):
                return parent['className']+'#'+parent['methodName']+parent.get('signature','')+' ['+parent.get('kind','BUSINESS')+']'
    return None


def load_json(text):
    def reject(value):
        raise InvalidAnalysis('Non-finite JSON number: '+value)
    def unique_object(pairs):
        result={}
        for key,value in pairs:
            require(key not in result,'Duplicate JSON field: '+key)
            result[key]=value
        return result
    return json.loads(text,parse_float=Decimal,parse_constant=reject,object_pairs_hook=unique_object)


def load_file(path,limit=128*1024*1024):
    opener=gzip.open if path.name.endswith('.gz') else open
    with opener(path,'rb') as stream:
        data=stream.read(limit+1)
    require(len(data)<=limit,'JSON source exceeds byte limit')
    return load_json(data.decode('utf-8'))


def jsonl_record(path,line):
    require(type(line) is int and 0<line<=2**63-1,'JSONL citation needs a positive line')
    opener=gzip.open if path.name.endswith('.gz') else open
    total=0
    with opener(path,'rb') as stream:
        for number in range(1,line+1):
            raw=stream.readline(4*1024*1024+1);total+=len(raw)
            require(raw and len(raw)<=4*1024*1024 and total<=128*1024*1024,'JSONL line missing or source/record exceeds limit')
            if number==line:return load_json(raw.decode('utf-8'))

def verify_indexed_reference(session,evidence):
    catalog_path=(session/'evidence/catalog.json').resolve();require(catalog_path.is_relative_to(session),'Index escapes session')
    catalog=load_file(catalog_path,4*1024*1024)
    require(catalog.get('identityValidated') is True,'Old evidence index must be rebuilt')
    require(catalog.get('sessionId')==load_file(session/'session.json',65536).get('sessionId'),'Index/session mismatch')
    index=(session/'evidence'/catalog['index']).resolve();require(index.is_relative_to(session),'Index escapes session')
    def sha(path):
        require(path.stat().st_size<=128*1024*1024,'Source exceeds byte limit')
        value=hashlib.sha256()
        with path.open('rb') as stream:
            for block in iter(lambda:stream.read(65536),b''):value.update(block)
        return value.hexdigest()
    require(index.stat().st_size<=64*1024*1024+65536,'Index exceeds byte limit')
    require(sha(index)==catalog['indexSha256'],'Index integrity check failed')
    row=None
    with index.open('rb') as stream:
        while True:
            raw=stream.readline(4*1024*1024+1)
            if not raw:break
            require(len(raw)<=4*1024*1024,'Index record too large')
            candidate=load_json(raw.decode('utf-8'))
            if candidate['evidenceId']==evidence['evidenceId']:row=candidate;break
    require(row is not None,'Unknown evidenceId')
    require(catalog['sessionId']==row['sessionId'],'Index/session mismatch')
    base=row['jsonPointer'];pointer=evidence['jsonPointer']
    require(row['file']==evidence['file'].replace(chr(92),'/') and row['line']==evidence.get('line',0) and (pointer==base or pointer.startswith(base+'/')),'Evidence ID citation mismatch')
    sources=[v for v in catalog['sources'] if v['file']==row['file']]
    require(len(sources)==1,'Missing indexed source')
    source=(session/row['file']).resolve();require(source.is_relative_to(session),'Source escapes session')
    require(sha(source)==sources[0]['sha256'],'Source hash changed; rebuild index')

def validate(response, session, schema):
    schema_check(response,schema)
    session=pathlib.Path(session).resolve()
    def artifact(name):
        file=session/name
        if not file.exists():
            return {}
        actual=file.resolve()
        require(actual.is_relative_to(session),'Artifact path escapes session directory')
        return load_file(actual)
    report=artifact('reports/performance-report.json')
    identity=artifact('session.json')
    diagnostics=artifact('diagnostics/manifest.json')
    requests=artifact('agent-logs/request-metrics.json')
    identities=[item['sessionId'] for item in (report,identity,diagnostics,requests) if item.get('sessionId')]
    require(bool(identities) and all(value==response['sessionId'] for value in identities),'Session ID mismatch across artifacts')
    quality=response['quality']
    for key in ('captureComplete','analysisReady'):
        require(quality[key] is report.get(key),f'Quality field {key} differs from source or unknown')
    source_quality=report.get('captureQuality',{})
    exporter=source_quality.get('agentExporter',{})
    manifest=artifact('spans/manifest.json')
    counts=[exporter.get(k) for k in ('queueDroppedSpans','deliveryDroppedSpans','rejectedDestinations','pendingSpans')]
    counts += [source_quality.get(k) for k in ('discardedSpans','unreceivedSpans')]
    roots=report.get('observedRootTraces',0)
    roots=roots if type(roots) is int and roots>=0 else 0
    complete=type(report.get('schemaVersion')) is int and report.get('schemaVersion')==1 and roots>0 and report.get('status')=='COMPLETED' and report.get('captureComplete') is True and report.get('analysisReady') is True
    complete=complete and type(exporter.get('enqueuedSpans')) is int and type(source_quality.get('acceptedSpans')) is int and source_quality.get('acceptedSpans')==exporter.get('enqueuedSpans')
    complete=complete and bool(identity.get('sessionId')) and exporter.get('reported') is True and all(type(n) is int and n==0 for n in counts) and manifest.get('captureComplete') is True
    if quality['verdict']=='usable':
        require(complete,'Usable verdict is unsupported by completeness evidence')
    else:
        require(bool(quality['issues']),'Partial/insufficient verdict must explain the missing evidence')
    diagnostic_samples=any(type(diagnostics.get(key)) is int and diagnostics[key]>0 for key in ('samples','threadSnapshots'))
    has_diagnostics=diagnostics.get('sessionId')==response['sessionId'] and type(diagnostics.get('schemaVersion')) is int and diagnostics.get('schemaVersion')==1 and diagnostic_samples
    has_requests=requests.get('sessionId')==response['sessionId'] and type(requests.get('schemaVersion')) is int and requests.get('schemaVersion')==1 and requests.get('enabled') is True and requests.get('status')=='FINAL' and type(requests.get('completedRequests')) is int and requests['completedRequests']>0 and isinstance(requests.get('endpoints'),list) and bool(requests['endpoints'])
    if roots==0 and not has_diagnostics and not has_requests:
        require(not response['findings'],'No valid trace, diagnostic or request metric samples: findings must be empty')
    checked=0
    for finding in response['findings']:
        cites_diagnostics=False
        require(len(finding['evidence'])<=64,'At most 64 evidence references per finding')
        for evidence in finding['evidence']:
            path=(session/evidence['file']).resolve()
            require(path.is_relative_to(session),'Evidence path escapes session directory')
            cites_diagnostics |= path.is_relative_to(session/'diagnostics')
            cites_diagnostics |= has_requests and path==session/'agent-logs/request-metrics.json'
            require(path.is_file(),'Evidence file does not exist: '+evidence['file'])
            require(any(path.name.endswith(ext) for ext in ('.json','.json.gz','.jsonl','.jsonl.gz')),'Validator supports JSON/JSONL artifacts')
            if 'evidenceId' in evidence:verify_indexed_reference(session,evidence)
            is_jsonl=path.name.endswith(('.jsonl','.jsonl.gz'))
            if not is_jsonl:require('line' not in evidence,'JSON artifact must not specify line')
            document=jsonl_record(path,evidence.get('line')) if is_jsonl else load_file(path)
            actual,parents=pointer(document,evidence['jsonPointer'])
            source_session=nearest_identity(parents,'session')
            if source_session is not None:
                require(source_session==response['sessionId'],'Evidence artifact belongs to another session')
            require(same_json(actual,evidence['value']),'Evidence value differs from source')
            unit=expected_unit(actual,evidence['jsonPointer'])
            if unit is not None:
                require(evidence['unit']==unit,'Evidence has a mismatched unit: expected '+unit)
            require(evidence['traceId']==nearest_identity(parents,'trace'),'Trace ID differs from nearest cited context or is missing')
            require(evidence['method']==nearest_identity(parents,'method'),'Method differs from nearest cited context or is missing')
            checked+=1
        if roots==0:
            require(cites_diagnostics,'Rootless findings require a verified diagnostic or independent request metric JSON citation')
    return {'valid':True,'sessionId':response['sessionId'],'findings':len(response['findings']),'verifiedEvidenceItems':checked,
            'scope':'contract, quality gate and exact local JSON citations; does not prove root cause, proposed benefit or model accuracy'}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session',required=True)
    parser.add_argument('--response',required=True,help='Plain JSON extracted from the AI response')
    parser.add_argument('--schema',help='Defaults to the session contract, with a repository fallback for older sessions')
    args=parser.parse_args()
    schema_path=pathlib.Path(args.schema) if args.schema else pathlib.Path(args.session)/'reports/ai-analysis-output.schema.json'
    if not args.schema and not schema_path.is_file():
        schema_path=pathlib.Path(__file__).resolve().parents[1]/'monitor-core/src/main/resources/com/agentmonitor/core/report/ai-analysis-output.schema.json'
    try:
        print(json.dumps(validate(load_file(pathlib.Path(args.response),4*1024*1024),args.session,load_file(schema_path,4*1024*1024)),ensure_ascii=False,indent=2))
    except (InvalidAnalysis,OSError,json.JSONDecodeError,UnicodeError) as error:
        parser.exit(1,str(error)+'\n')
