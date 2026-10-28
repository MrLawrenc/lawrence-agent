import copy
import importlib.util
import json
import hashlib
import pathlib
import tempfile
import unittest
from decimal import Decimal

ROOT=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('ai_validator',ROOT/'scripts/verify-ai-analysis.py')
validator=importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
SCHEMA=json.loads((ROOT/'monitor-core/src/main/resources/com/agentmonitor/core/report/ai-analysis-output.schema.json').read_text())


class AiEvidenceValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.session=pathlib.Path(self.temp.name)
        (self.session/'reports').mkdir();(self.session/'spans').mkdir()
        self.report={'schemaVersion':1,'sessionId':'s','status':'COMPLETED','captureComplete':True,'analysisReady':True,'observedRootTraces':1,
            'captureQuality':{'acceptedSpans':1,'agentExporter':{'enqueuedSpans':1,'reported':True,'queueDroppedSpans':0,'deliveryDroppedSpans':0,'rejectedDestinations':0,'pendingSpans':0},'discardedSpans':0,'unreceivedSpans':0},
            'slowTraces':[{'traceId':'trace-a','rootMethod':'example.Service#work()V [BUSINESS]','durationNanos':1000000}]}
        self.save()
        (self.session/'session.json').write_text('{"sessionId":"s"}')
        (self.session/'spans/manifest.json').write_text('{"captureComplete":true}')
        self.response={'schemaVersion':1,'sessionId':'s','quality':{'verdict':'usable','captureComplete':True,'analysisReady':True,'populationStatisticsAllowed':False,'issues':[]},
            'findings':[{'id':'F1','priority':'P3','title':'Observed call','observation':'The recorded call took 1 ms; no SLO supplied.',
                'evidence':[{'file':'reports/performance-report.json','jsonPointer':'/slowTraces/0/durationNanos','traceId':'trace-a','method':'example.Service#work()V [BUSINESS]','value':1000000,'unit':'nanoseconds'}],
                'hypothesis':None,'confidence':'high','missingEvidence':['Same-load baseline'],'recommendation':'Reproduce with a baseline before changing code.',
                'validation':{'baseline':'Warm the same workload.','change':'One isolated candidate change.','metrics':['Business duration'],'acceptance':'Same semantics and a repeatable measured improvement.','risks':['Observer effects']}}],
            'nextMeasurements':['Collect a controlled baseline.']}
    def save(self):
        (self.session/'reports/performance-report.json').write_text(json.dumps(self.report))
    def verify(self,response=None):
        return validator.validate(response or self.response,self.session,SCHEMA)
    def test_valid_response_resolves_exact_value_trace_and_method(self):
        self.assertEqual(1,self.verify()['verifiedEvidenceItems'])
    def test_dropped_or_unknown_quality_cannot_claim_usable(self):
        self.report['captureQuality']['unreceivedSpans']=1;self.save()
        with self.assertRaisesRegex(validator.InvalidAnalysis,'completeness'):self.verify()
        self.response['quality']['verdict']='partial';self.response['quality']['issues']=['One unreceived span.']
        self.assertTrue(self.verify()['valid'])
        del self.report['captureQuality'];self.save();self.response['quality']['verdict']='usable'
        with self.assertRaises(validator.InvalidAnalysis):self.verify()
    def test_inconsistent_exporter_receive_totals_cannot_claim_usable(self):
        self.report['captureQuality']['agentExporter']['enqueuedSpans']=2;self.save()
        with self.assertRaisesRegex(validator.InvalidAnalysis,'completeness'):self.verify()
    def test_empty_session_requires_no_findings(self):
        self.report['observedRootTraces']=0;self.report['analysisReady']=False;self.save()
        self.response['quality'].update(verdict='insufficient',analysisReady=False,issues=['No root samples.'])
        with self.assertRaisesRegex(validator.InvalidAnalysis,'findings'):self.verify()
        self.response['findings']=[];self.assertTrue(self.verify()['valid'])
    def test_contract_rejects_missing_evidence_bad_units_and_illegal_pointer(self):
        for key,value in [('jsonPointer','/slowTraces/0/durationNanos~2'),('unit','seconds')]:
            response=copy.deepcopy(self.response);response['findings'][0]['evidence'][0][key]=value
            with self.assertRaises(validator.InvalidAnalysis):self.verify(response)
        response=copy.deepcopy(self.response);response['findings'][0]['evidence']=[]
        with self.assertRaises(validator.InvalidAnalysis):self.verify(response)
    def test_fabricated_values_trace_ids_methods_and_unit_conversions_are_rejected(self):
        for key,value in [('value',42),('traceId','fabricated'),('method','fake.Service#run()V'),('unit','milliseconds')]:
            response=copy.deepcopy(self.response);response['findings'][0]['evidence'][0][key]=value
            with self.assertRaises(validator.InvalidAnalysis):self.verify(response)
    def test_cross_session_and_outside_paths_are_rejected(self):
        response=copy.deepcopy(self.response);response['sessionId']='other'
        with self.assertRaises(validator.InvalidAnalysis):self.verify(response)
        response=copy.deepcopy(self.response);response['findings'][0]['evidence'][0]['file']='../outside.json'
        with self.assertRaisesRegex(validator.InvalidAnalysis,'escapes'):self.verify(response)
    def test_leaf_method_cannot_be_replaced_by_ancestor_or_null_identity(self):
        trace=self.report['slowTraces'][0]
        trace['bottlenecks']=[{'method':'example.Dao#query()V [SQL]','selfNanos':700000}]
        self.save()
        response=copy.deepcopy(self.response)
        evidence=response['findings'][0]['evidence'][0]
        evidence.update(jsonPointer='/slowTraces/0/bottlenecks/0/selfNanos',value=700000)
        with self.assertRaisesRegex(validator.InvalidAnalysis,'Method'):self.verify(response)
        evidence['method']='example.Dao#query()V [SQL]'
        self.assertTrue(self.verify(response)['valid'])
        evidence['traceId']=None
        with self.assertRaisesRegex(validator.InvalidAnalysis,'Trace ID'):self.verify(response)
        evidence['traceId']='trace-a';evidence['method']=None
        with self.assertRaisesRegex(validator.InvalidAnalysis,'Method'):self.verify(response)
    def test_conflicting_artifact_ids_are_rejected(self):
        (self.session/'session.json').write_text('{"sessionId":"other"}')
        with self.assertRaisesRegex(validator.InvalidAnalysis,'Session ID'):self.verify()
    def test_count_units_and_nested_json_boolean_types_are_exact(self):
        self.report['hotspots']=[{'observedCalls':7,'method':'example.Service#work()V [BUSINESS]'}]
        self.report['condition']={'value':True};self.save()
        response=copy.deepcopy(self.response);evidence=response['findings'][0]['evidence'][0]
        evidence.update(jsonPointer='/hotspots/0/observedCalls',value=7,unit='milliseconds',traceId=None)
        with self.assertRaisesRegex(validator.InvalidAnalysis,'unit'):self.verify(response)
        evidence['unit']='count';self.assertTrue(self.verify(response)['valid'])
        evidence.update(jsonPointer='/condition',value={'value':1},unit='text',method=None)
        with self.assertRaisesRegex(validator.InvalidAnalysis,'value'):self.verify(response)
        evidence['value']={'value':True};self.assertTrue(self.verify(response)['valid'])
    def test_decimal_evidence_preserves_precision(self):
        self.report['customPercent']=0.1;self.save()
        response=copy.deepcopy(self.response);e=response['findings'][0]['evidence'][0]
        e.update(jsonPointer='/customPercent',value=Decimal('0.10000000000000001'),unit='percent',traceId=None,method=None)
        with self.assertRaisesRegex(validator.InvalidAnalysis,'value'):self.verify(response)
        e['value']=Decimal('0.1');self.assertTrue(self.verify(response)['valid'])
    def test_duplicate_fields_are_rejected_in_responses_and_evidence(self):
        with self.assertRaisesRegex(validator.InvalidAnalysis,'Duplicate JSON field'):
            validator.load_json('{"sessionId":"s","sessionId":"other"}')
        (self.session/'reports/duplicate.json').write_text('{"count":1,"count":2}')
        response=copy.deepcopy(self.response);e=response['findings'][0]['evidence'][0]
        e.update(file='reports/duplicate.json',jsonPointer='/count',value=2,unit='count',traceId=None,method=None)
        with self.assertRaisesRegex(validator.InvalidAnalysis,'Duplicate JSON field'):self.verify(response)
    def test_diagnostic_only_findings_are_partial_and_need_session_bound_samples(self):
        self.report['observedRootTraces']=0;self.report['analysisReady']=False;self.save()
        diagnostic=self.session/'diagnostics';diagnostic.mkdir()
        (diagnostic/'manifest.json').write_text('{"schemaVersion":1,"sessionId":"s","samples":1}')
        (diagnostic/'latest.json').write_text('{"sessionId":"s","ProcessCpuLoad":0.1}')
        response=copy.deepcopy(self.response);response['quality'].update(verdict='partial',analysisReady=False,issues=['No trace roots.'])
        e=response['findings'][0]['evidence'][0]
        e.update(file='diagnostics/latest.json',jsonPointer='/ProcessCpuLoad',value=Decimal('0.1'),unit='ratio',traceId=None,method=None)
        self.assertTrue(self.verify(response)['valid'])
        response['quality']['verdict']='usable'
        with self.assertRaises(validator.InvalidAnalysis):self.verify(response)
        response['quality']['verdict']='partial'
        (diagnostic/'latest.json').write_text('{"sessionId":"other","ProcessCpuLoad":0.1}')
        with self.assertRaisesRegex(validator.InvalidAnalysis,'session'):self.verify(response)
        (diagnostic/'manifest.json').write_text('{"schemaVersion":1,"sessionId":"s","samples":0}')
        with self.assertRaises(validator.InvalidAnalysis):self.verify(response)
    def test_json_pointer_escape_and_root_semantics(self):
        document={'a/b':{'~key':7}}
        self.assertEqual(7,validator.pointer(document,'/a~1b/~0key')[0])
        self.assertEqual(document,validator.pointer(document,'')[0])
        with self.assertRaises(validator.InvalidAnalysis):validator.pointer(document,'/a~2b')

    def test_historical_jsonl_requires_exact_line_and_value(self):
        folder=self.session/'diagnostics';folder.mkdir()
        (folder/'metrics.jsonl').write_text('{"sessionId":"s","ProcessCpuLoad":0.1}\n{"sessionId":"s","ProcessCpuLoad":0.9}\n')
        response=copy.deepcopy(self.response);e=response['findings'][0]['evidence'][0]
        e.update(file='diagnostics/metrics.jsonl',line=1,jsonPointer='/ProcessCpuLoad',value=Decimal('0.1'),unit='ratio',traceId=None,method=None)
        self.assertTrue(self.verify(response)['valid']);e['line']=2
        with self.assertRaises(validator.InvalidAnalysis):self.verify(response)
        e['value']=Decimal('0.9');self.assertTrue(self.verify(response)['valid']);e['line']=0
        with self.assertRaises(validator.InvalidAnalysis):self.verify(response)

    def test_indexed_reference_checks_source_hash_and_marker_identity(self):
        folder=self.session/'diagnostics';folder.mkdir();source=folder/'metrics.jsonl';source.write_text('{"sessionId":"s","ProcessCpuLoad":0.1}\n')
        index=self.session/'evidence';index.mkdir();identifier='1'*64
        row={'evidenceId':identifier,'sessionId':'s','file':'diagnostics/metrics.jsonl','line':1,'jsonPointer':''}
        data=json.dumps(row)+'\n';(index/'index.jsonl').write_text(data)
        catalog={'sessionId':'s','identityValidated':True,'index':'index.jsonl','indexSha256':hashlib.sha256(data.encode()).hexdigest(),
                 'sources':[{'file':'diagnostics/metrics.jsonl','sha256':hashlib.sha256(source.read_bytes()).hexdigest()}]}
        (index/'catalog.json').write_text(json.dumps(catalog))
        response=copy.deepcopy(self.response);e=response['findings'][0]['evidence'][0]
        e.update(file='diagnostics/metrics.jsonl',line=1,evidenceId=identifier,jsonPointer='/ProcessCpuLoad',value=Decimal('0.1'),unit='ratio',traceId=None,method=None)
        self.assertTrue(self.verify(response)['valid']);catalog['sessionId']='other';(index/'catalog.json').write_text(json.dumps(catalog))
        with self.assertRaises(validator.InvalidAnalysis):self.verify(response)
        catalog['sessionId']='s';(index/'catalog.json').write_text(json.dumps(catalog));source.write_text(source.read_text()+' ')
        with self.assertRaises(validator.InvalidAnalysis):self.verify(response)

    def test_independent_request_metrics_allow_partial_findings_without_trace_samples(self):
        logs=self.session/'agent-logs';logs.mkdir()
        data={'schemaVersion':1,'sessionId':'s','enabled':True,'status':'FINAL','completedRequests':7,'endpoints':[{'completed':7,'completedPerSecond':2.5}]}
        source=logs/'request-metrics.json';source.write_text(json.dumps(data))
        self.report.update(observedRootTraces=0,analysisReady=False);self.save()
        self.response['quality'].update(verdict='partial',analysisReady=False,issues=['Independent Servlet window only'])
        e=self.response['findings'][0]['evidence'][0];e.update(file='agent-logs/request-metrics.json',jsonPointer='/endpoints/0/completed',traceId=None,method=None,value=7,unit='count')
        self.assertTrue(self.verify()['valid']);e['unit']='milliseconds'
        with self.assertRaises(validator.InvalidAnalysis):self.verify()
        e.update(jsonPointer='/endpoints/0/completedPerSecond',value=2.5,unit='per_second');self.assertTrue(self.verify()['valid']);e['unit']='ratio'
        with self.assertRaises(validator.InvalidAnalysis):self.verify()
        data['sessionId']='foreign';source.write_text(json.dumps(data))
        with self.assertRaises(validator.InvalidAnalysis):self.verify()

if __name__=='__main__':unittest.main()
