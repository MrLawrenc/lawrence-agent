#!/usr/bin/env python3
"""Build and test the monitor against an isolated, real Docker/Colima Java stack (Python 3.9+)."""
import argparse
import collections
import concurrent.futures
import hashlib
import json
import os
import pathlib
import shutil
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import uuid

HERE=pathlib.Path(__file__).resolve().parent
ROOT=HERE.parents[1]
SERVICES=('gateway','inventory','pricing')


def save(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context',default='colima')
    parser.add_argument('--skip-build',action='store_true')
    parser.add_argument('--keep',action='store_true',help='Leave this test project running for manual inspection')
    parser.add_argument('--prepare-only',action='store_true',help='Start and identify the fixture without collecting; requires --keep')
    parser.add_argument('--publish-dependencies',action='store_true',help='Publish this project dependencies on random localhost ports for a desktop JVM')
    parser.add_argument('--capture-seconds',type=int,default=60)
    args=parser.parse_args()
    if args.prepare_only and not args.keep:parser.error('--prepare-only requires --keep; stop this exact project after skill jobs finish')
    if not 40<=args.capture_seconds<=300:parser.error('--capture-seconds: 40–300')
    stamp=time.strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
    project='agent-monitor-lab-'+stamp.lower()
    run=HERE/'build/runs'/stamp
    for folder in ('captures','config','analysis','logs','docker-client'):(run/folder).mkdir(parents=True,exist_ok=True)
    (HERE/'build/latest.txt').write_text(str(run))
    endpoint=subprocess.check_output(['docker','context','inspect',args.context,'--format','{{.Endpoints.docker.Host}}'],text=True).strip()
    if not endpoint.startswith('unix://'):raise ValueError('This fixture requires a local Docker/Colima Unix socket')
    # Public images only: do not inherit a Docker Desktop credential helper or modify global context/DNS.
    save(run/'docker-client/config.json',{'cliPluginsExtraDirs':[str(pathlib.Path.home()/'.docker/cli-plugins'),'/opt/homebrew/lib/docker/cli-plugins','/Applications/Docker.app/Contents/Resources/cli-plugins']})
    docker=['docker','--config',str(run/'docker-client'),'--host',endpoint]
    env=dict(os.environ,LAB_RUN_DIR=str(run),LAB_IMAGE=project+':local')
    compose=docker+['compose','-f',str(HERE/'compose.yaml'),'-p',project]
    if args.publish_dependencies:
        override=run/'compose.host.json'
        save(override,{'services':{name:{'ports':['127.0.0.1::'+str(port)]} for name,port in [('postgres',5432),('redis',6379),('rabbitmq',5672)]}})
        compose+=['-f',str(override)]
    summary={'project':project,'context':args.context,'endpoint':endpoint,'status':'RUNNING','runDirectory':str(run),'checks':{},'limitations':['Functional integration and a small fixed-work A/B/A observation; not production capacity or a proven observer overhead estimate.','RabbitMQ publish is traced; broker confirmation and consumer completion are verified by the fixture, not instrumented as distributed consumer spans.']}
    captures=[];started=False
    def command(label,argv,timeout=180,accepted=(0,)):
        with (run/'logs'/(label+'.stdout')).open('w') as out,(run/'logs'/(label+'.stderr')).open('w') as err:
            result=subprocess.run(argv,env=env,cwd=ROOT,stdout=out,stderr=err,timeout=timeout)
        if result.returncode not in accepted:raise RuntimeError(label+' failed ('+str(result.returncode)+'); see '+str(run/'logs'/(label+'.stderr')))
        return (run/'logs'/(label+'.stdout')).read_text()
    def cli(label,service,arguments,accepted=(0,)):
        text=command(label,docker+['exec',containers[service],'java','-Xmx160m','--add-modules','jdk.attach','-jar','/opt/monitor/monitor-cli.jar']+arguments,accepted=accepted)
        data=json.loads(text);save(run/'analysis'/(label+'.json'),data);return data
    def request(service,path,body=None,expected=200):
        headers={'Content-Type':'application/octet-stream'} if body is not None else {}
        start=time.perf_counter()
        try:
            with urllib.request.urlopen(urllib.request.Request(addresses[service]+path,data=body,headers=headers),timeout=15) as response:status=response.status;raw=response.read()
        except urllib.error.HTTPError as error:status=error.code;raw=error.read()
        if status!=expected:raise AssertionError((service,path,status,raw[:1000]))
        ledger[service][path.split('?')[0]]+=1
        return (raw if body is not None else json.loads(raw)),(time.perf_counter()-start)*1000
    def benchmark(label):
        for _ in range(12):request('gateway','/cpu')
        values=[request('gateway','/cpu')[1] for _ in range(36)]
        data={'requests':len(values),'concurrency':1,'latencyMillis':values,'medianMillis':statistics.median(values),'p95Millis':sorted(values)[int(.95*(len(values)-1))]}
        save(run/'analysis'/('load-'+label+'.json'),data);return data
    try:
        print('RUN',run,flush=True)
        summary['existingContainers']=command('existing',docker+['ps','-q']).split()
        summary['engine']=json.loads(command('engine',docker+['info','--format','{"version":"{{.ServerVersion}}","cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}},"architecture":"{{.Architecture}}","cgroupVersion":"{{.CgroupVersion}}"}']))
        if not args.skip_build:
            print('Building Java fixture and CLI',flush=True)
            command('build-fixture',[str(ROOT/'gradlew'),'--console=plain','-p',str(HERE),'installDist'],600)
            command('build-cli',[str(ROOT/'gradlew'),'--console=plain',':monitor-cli:shadowJar'],600)
            shutil.copyfile(ROOT/'monitor-cli/build/libs/monitor-cli-all.jar',HERE/'build/monitor-cli-all.jar')
            print('Building Docker image from the cached JDK base',flush=True)
            env['DOCKER_BUILDKIT']='0'
            command('build-image',docker+['build','--pull=false','-t',env['LAB_IMAGE'],'-t','agent-monitor-lab:local',str(HERE)],300)
        else:
            command('reuse-image',docker+['image','tag','agent-monitor-lab:local',env['LAB_IMAGE']])
        for service in SERVICES:
            config={'version':2,'scope':{'includePackages':['dev.perflab.work']},'resource':{'serviceName':service,'serviceVersion':'docker-lab','deploymentEnvironment':'local-fixture'},
                    'sampling':{'ratePercent':100,'tailCaptureThresholdMs':50},'output':{'exporters':['otlp','file'],'capture':{'arguments':False,'returnValue':False,'sqlParameters':False}},
                    'diagnostics':{'enabled':True,'intervalMs':1000,'threadIntervalMs':2000,'maxSamples':500,'jfr':True,'exceptionEvents':False},
                    'overhead':{'adviceBudgetNanosPerSecond':500000000}}
            save(run/'config'/(service+'.yml'),config)
        zero=json.loads((run/'config/gateway.yml').read_text());zero['sampling']={'ratePercent':0,'tailCaptureThresholdMs':60000};zero['diagnostics']['enabled']=False;zero['diagnostics']['jfr']=False
        save(run/'config/gateway-zero.yml',zero)
        command('compose-config',compose+['config'])
        print('Starting only this Compose project',flush=True);started=True
        command('up',compose+['up','-d','--no-build','--pull','never','--wait','--wait-timeout','180'],240)
        containers={s:command('container-'+s,compose+['ps','-q',s]).strip() for s in SERVICES}
        summary['containers']=containers
        summary['imageMetadata']=[]
        for image in (env['LAB_IMAGE'],'postgres:17-alpine','redis:7.4-alpine','rabbitmq:4.1-alpine'):
            image_info=json.loads(command('image-'+image.split(':')[0].replace('/','-'),docker+['image','inspect',image]))[0]
            summary['imageMetadata'].append({'image':image,'id':image_info['Id'],'digests':image_info.get('RepoDigests',[])})
        summary['cliSha256']=command('cli-sha256',docker+['exec',containers['gateway'],'sha256sum','/opt/monitor/monitor-cli.jar']).split()[0]
        summary['javaVersion']=command('java-version',docker+['exec',containers['gateway'],'java','-version'])+(run/'logs/java-version.stderr').read_text()
        addresses={s:'http://'+command('port-'+s,compose+['port',s,'8080']).strip() for s in SERVICES}
        summary['addresses']=addresses
        save(run/'control.json',{'docker':docker,'compose':compose,'environment':{'LAB_RUN_DIR':str(run),'LAB_IMAGE':env['LAB_IMAGE']},'containers':containers,'addresses':addresses})
        ledger={s:collections.Counter() for s in SERVICES}
        for service in SERVICES:assert request(service,'/health')[0]['pid']==1
        if args.prepare_only:
            summary['status']='PREPARED'
            summary['limitations']=['Fixture prepared only; no performance capture or analysis has run.']
            return 0
        # Warm optional clients before attach. The later measurements use the same long-lived JVMs.
        for path in ('/chain','/redis','/rabbit','/apache','/slow-sql'):request('gateway',path)
        summary['load']={'before':benchmark('before')}
        print('Attaching the CLI inside each container/PID namespace',flush=True)
        for service in SERVICES:
            identity=cli('identity-'+service,service,['jvm','identity','--pid','1'])
            arguments=['capture','--pid','1','--expected-start',identity['startedAt'],'--config','/config/'+service+'.yml','--output','/captures/'+service,'--duration',str(args.capture_seconds)+'s']
            if service=='gateway':arguments+=['--rolling','--window','10s','--max-bytes','134217728']
            out=(run/'logs'/('capture-'+service+'.json')).open('w');err=(run/'logs'/('capture-'+service+'.log')).open('w')
            process=subprocess.Popen(docker+['exec',containers[service],'java','-Xmx160m','--add-modules','jdk.attach','-jar','/opt/monitor/monitor-cli.jar']+arguments,env=env,stdout=out,stderr=err)
            capture={'service':service,'process':process,'out':out,'err':err};captures.append(capture)
            deadline=time.monotonic()+45
            while time.monotonic()<deadline:
                text=(run/'logs'/('capture-'+service+'.log')).read_text()
                if 'READY pid=' in text:break
                if process.poll() is not None:raise RuntimeError('Capture exited before READY: '+service+'; '+text[-2000:])
                time.sleep(.25)
            else:raise TimeoutError('Capture READY timeout: '+service)
            capture['containerSession']=next(line[8:] for line in text.splitlines() if line.startswith('SESSION '))
            capture['session']=run/'captures'/pathlib.PurePosixPath(capture['containerSession']).relative_to('/captures')
            print('READY',service,flush=True)
        ledger={s:collections.Counter() for s in SERVICES}
        # Once all collectors are ready, send an exact deterministic workload.
        result,_=request('gateway','/chain');trace=result['children'][0]['incomingTraceparent'].split('-')[1]
        summary['chainResponse']=result
        for i in range(3):request('gateway','/items/'+str(i))
        sql,_=request('gateway','/slow-sql');assert sql['elapsedMillis']>=70
        wait,_=request('gateway','/pool-wait');assert wait['waitMillis']>=120
        assert request('gateway','/redis')[0]['value']=='fixture-value'
        assert request('gateway','/rabbit')[0]=={'confirmed':True,'bodyPreserved':True}
        apache,_=request('gateway','/apache');assert all(apache.values()),apache
        async_result,async_millis=request('gateway','/async');assert async_result['completed'] and async_millis>=200
        body=b'one-shot-body\x00\xff';assert request('gateway','/echo',body=body)[0]==body
        request('gateway','/failure',expected=503)
        for _ in range(4):request('gateway','/memory')
        summary['load']['during']=benchmark('during')
        summary['workload']={'sql':sql,'connectionWait':wait,'apache5':apache,'asyncClientObservedMillis':async_millis,'gatewayRequests':dict(ledger['gateway'])}
        print('Workload completed; checking natural STOP/restoration and rolling windows',flush=True)
        sessions={}
        for capture in captures:
            capture['process'].wait(timeout=args.capture_seconds+60);capture['out'].close();capture['err'].close()
            final=json.loads((run/'logs'/('capture-'+capture['service']+'.json')).read_text())
            assert capture['process'].returncode==0,final
            assert final['restored'] and final['targetStillAlive'] and final['captureComplete'],final
            sessions[capture['service']]=capture['session'];summary['checks']['capture-'+capture['service']]=final
            assert request(capture['service'],'/health')[0]['pid']==1
        summary['load']['after']=benchmark('after')
        gateway=sessions['gateway'];container_gateway=next(c['containerSession'] for c in captures if c['service']=='gateway')
        stats=cli('requests','gateway',['requests','--session',container_gateway]);assert stats['pendingRequests']==0 and stats['captureComplete']
        endpoints={row['endpoint']:row for row in stats['endpoints']}
        assert endpoints['GET /items/{id}']['completed']==3,endpoints.keys()
        assert endpoints['GET /failure']['errors']==1
        assert endpoints['GET /async']['maxMillis']>=200
        for endpoint,count in summary['workload']['gatewayRequests'].items():
            if endpoint.startswith('/items/'):continue
            prefix='POST ' if endpoint=='/echo' else 'GET '
            assert endpoints[prefix+endpoint]['completed']==count,(endpoint,count,endpoints.get(prefix+endpoint))
        summary['checks']['independentRequestMetrics']={'completed':stats['completedRequests'],'pending':stats['pendingRequests'],'routeGrouping':True,'asyncCompleted':True,'errorsCounted':True}
        report=json.loads((gateway/'reports/performance-report.json').read_text());aggregates=report['diagnosticAggregates']
        assert any('dev.perflab.work.LabWork#cpu' in row['method'] for row in report['hotspots']), 'Business method instrumentation missing'
        assert aggregates['sql'] and aggregates['connectionAcquisition']
        dependency_text=json.dumps(aggregates['dependencies']);assert all(kind in dependency_text for kind in ('HTTP_CLIENT','REDIS','MESSAGING')),dependency_text
        for service,session in sessions.items():
            manifest=json.loads((session/'diagnostics/manifest.json').read_text());assert manifest['samples']>1 and not manifest['errors'],manifest
            assert (session/'diagnostics/profile.jfr').stat().st_size>0
            summary['checks']['diagnostics-'+service]={'samples':manifest['samples'],'status':manifest['status'],'jfrBytes':(session/'diagnostics/profile.jfr').stat().st_size}
        rolling=json.loads((gateway/'rolling-capture.json').read_text());assert len(rolling['windows'])>=2 and not rolling['errors']
        summary['checks']['rollingWindows']=len(rolling['windows'])
        # Correlation runs inside the container against its original paths, preserving service identities.
        container_sessions=[next(c['containerSession'] for c in captures if c['service']==s) for s in SERVICES]
        joined=cli('distributed','gateway',['distributed','--sessions',','.join(container_sessions),'--trace',trace])
        assert not joined['missingParents'],joined['missingParents'];assert sum(edge['crossSession'] for edge in joined['edges'])==4
        summary['checks']['distributed']={'traceId':trace,'spans':len(joined['spans']),'crossSessionEdges':4}
        cli('diagnose','gateway',['diagnose','--session',container_gateway,'--symptom','errors'])
        cli('evidence-index','gateway',['evidence','index','--session',container_gateway])
        queried=cli('evidence-query','gateway',['evidence','query','--session',container_gateway,'--kind','request_metrics','--method','/failure'])
        evidence=queried['matches'][0];assert evidence['method']==''
        verified=cli('evidence-get','gateway',['evidence','get','--session',container_gateway,'--id',evidence['evidenceId']]);assert verified['verified']
        response={'schemaVersion':1,'sessionId':stats['sessionId'],'quality':{'verdict':'partial','captureComplete':report['captureComplete'],'analysisReady':report['analysisReady'],'populationStatisticsAllowed':False,'issues':['Controlled Docker fixture; no production workload or SLO.']},
                  'findings':[{'id':'F1','priority':'P2','title':'Observed fixture failure','observation':'One 503 request in the captured window.','evidence':[{'file':evidence['file'],'jsonPointer':evidence['jsonPointer']+'/errors','value':1,'unit':'count','traceId':None,'method':None,'evidenceId':evidence['evidenceId']}],
                               'hypothesis':None,'confidence':'high','missingEvidence':['Production workload'],'recommendation':'Inspect the corresponding request/exception before changing code.','validation':{'baseline':'Same deterministic workload','change':'One behavior-preserving change','metrics':['Errors','Latency'],'acceptance':'Correct result and repeatable measurements','risks':['Observer effects']}}], 'nextMeasurements':['Run longer under representative load.']}
        save(run/'analysis/ai-response.json',response)
        # Only captures are mounted; copy this derived AI output explicitly without altering raw data.
        command('copy-ai',docker+['cp',str(run/'analysis/ai-response.json'),containers['gateway']+':/tmp/lab-ai-response.json'])
        validated=cli('ai-verify','gateway',['ai','verify','--session',container_gateway,'--response','/tmp/lab-ai-response.json']);assert validated['valid']
        summary['checks']['aiEvidenceVerified']=validated['verifiedEvidenceItems']
        print('Reattaching with zero trace sampling to verify independent ingress counters',flush=True)
        zero_args=['capture','--pid','1','--config','/config/gateway-zero.yml','--output','/captures/zero','--duration','6s']
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as worker:
            pending=worker.submit(cli,'zero-capture','gateway',zero_args)
            deadline=time.monotonic()+30
            while time.monotonic()<deadline:
                log=run/'logs/zero-capture.stderr'
                if log.exists() and 'READY pid=' in log.read_text():break
                if pending.done():pending.result();raise RuntimeError('Zero-sampling capture ended before ready')
                time.sleep(.2)
            else:raise TimeoutError('Zero-sampling attach readiness timed out')
            request('gateway','/items/9001');request('gateway','/items/9002');zero_capture=pending.result(timeout=30)
        assert zero_capture['restored'] and zero_capture['receivedSpans']==0,zero_capture
        zero_metrics=cli('zero-requests','gateway',['requests','--session',zero_capture['sessionDirectory']]);assert zero_metrics['completedRequests']==2 and zero_metrics['pendingRequests']==0
        assert request('gateway','/health')[0]['pid']==1
        summary['checks']['zeroSamplingReattach']={'restored':True,'receivedSpans':0,'completedRequests':2,'targetStillAlive':True}
        summary['status']='PASSED'
        summary['sessionDirectories']={s:str(p) for s,p in sessions.items()}
    except BaseException as error:
        summary['status']='FAILED';summary['error']=str(error)
        raise
    finally:
        # A failed assertion still preserves all raw evidence and logs. No unrelated project is stopped.
        for capture in captures:
            if capture['process'].poll() is None:
                try:capture['process'].wait(timeout=args.capture_seconds+45)
                except subprocess.TimeoutExpired:summary.setdefault('cleanupWarnings',[]).append('Monitor still active for '+capture['service'])
            capture['out'].close();capture['err'].close()
        if started:
            try:command('compose-logs',compose+['logs','--no-color'],60)
            except Exception as error:summary.setdefault('cleanupWarnings',[]).append(str(error))
            if not args.keep:
                try:command('down',compose+['down','--volumes','--remove-orphans'],150);summary['testProjectRemoved']=True
                except Exception as error:summary.setdefault('cleanupWarnings',[]).append(str(error))
        try:
            remaining=command('remaining-containers',docker+['ps','-q']).split()
            summary['existingContainersStillRunning']=set(summary.get('existingContainers',[])).issubset(remaining)
        except Exception as error:summary.setdefault('cleanupWarnings',[]).append(str(error))
        save(run/'summary.json',summary)
        print('RESULT',summary['status'],run/'summary.json',flush=True)
    return 0


if __name__=='__main__':raise SystemExit(main())
