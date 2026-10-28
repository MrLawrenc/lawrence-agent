#!/usr/bin/env python3
"""Install/reuse monitor; run durable, bounded capture jobs without a resident AI."""
import argparse
import json
import math
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import time
import uuid

from runtime import MAX_JSON, atomic_json, cli_json, duration, ensure_cli, identity, read_json

TERMINAL = {"completed", "degraded", "stopped", "failed", "restore_unconfirmed", "quota_reached", "target_changed", "interrupted"}
STOP_GRACE = 30
HEARTBEAT = 2


def now():
    return int(time.time() * 1000)


def emit(value):
    print(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False))


def save_job(root, job):
    job["heartbeatMillis"] = now()
    atomic_json(root / "job.json", job)


def event(root, kind, **values):
    with (root / "events.jsonl").open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(dict(timestampMillis=now(), event=kind, **values), ensure_ascii=False) + "\n")
        stream.flush()


def safe_child(root, path):
    actual = pathlib.Path(path).resolve()
    if not actual.is_relative_to(root.resolve()):
        raise ValueError("Artifact escapes its job directory")
    return actual


def session_in(window):
    captures = window / "captures"
    for marker in captures.glob("*/session-*/session.json"):
        if marker.is_file():
            return str(safe_child(captures, marker.parent))
    return None


def directory_bytes(root):
    total = 0
    for directory, _, names in os.walk(root, followlinks=False):
        for name in names:
            path = pathlib.Path(directory) / name
            try:
                if not path.is_symlink():
                    total += path.stat().st_size
            except FileNotFoundError:
                pass
    return total


def start(args):
    if os.name != "posix":
        raise ValueError("Background supervision supports macOS/Linux. On Windows run the standalone CLI in the foreground.")
    total = duration(args.duration)
    window = duration(args.window, 3600)
    if math.ceil(total / window) > 10000:
        raise ValueError("Choose a longer window: at most 10000 windows per job")
    if args.max_bytes < 1024 * 1024:
        raise ValueError("--max-bytes must be at least 1MiB")
    installed = ensure_cli(cli=args.cli, install_root=args.install_root)
    command = installed["command"]
    target = identity(args.pid)
    processes = cli_json(command, ["jvm", "list"]).get("jvms", [])
    process = next((p for p in processes if str(p.get("pid")) == str(args.pid)), None)
    if not process:
        raise ValueError("PID is not in monitor jvm list; choose an accessible local JVM")
    jvm_identity=cli_json(command,["jvm","identity","--pid",str(args.pid)])
    configuration = None
    if args.config:
        path = pathlib.Path(args.config).expanduser().resolve()
        if not path.is_file() or path.stat().st_size > 1024 * 1024:
            raise ValueError("Config is missing or exceeds 1MiB")
        configuration = path.read_bytes()
        if args.mode == "capture":
            cli_json(command, ["config", "validate", "--config", str(path)])
    elif args.mode == "capture":
        raise ValueError("Trace capture requires --config with an explicit business scope")
    if identity(args.pid) != target:
        raise ValueError("Target changed during preflight")
    base = pathlib.Path(args.work_dir).expanduser().resolve()
    base.mkdir(parents=True, exist_ok=True)
    root = base / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:12])
    root.mkdir(mode=0o700)
    if configuration is not None:
        (root / "config.yml").write_bytes(configuration)
    job = {"schemaVersion": 1, "jobId": root.name, "status": "pending", "jobDirectory": str(root),
           "mode": args.mode, "target": target, "targetJvmStartedAt": jvm_identity["startedAt"], "targetDisplayName": process.get("displayName", ""),
           "createdAtMillis": now(), "requestedDurationMillis": round(total * 1000), "windowMillis": round(window * 1000),
           "maxBytes": args.max_bytes, "cliCommand": command, "cliSha256": installed.get("sha256"),
           "configuration": str(root / "config.yml") if configuration is not None else None,
           "completedWindows": 0, "degradedWindows": 0, "currentWindow": None, "workerPid": None,
           "nativeRolling": args.mode == "capture", "continuous": args.mode == "capture", "coverageNote": ("One Agent attachment; roots are archived by complete receipt window. Window exporter completeness remains unknown until the master final ACK; JFR checkpoints retain bounded tails." if args.mode == "capture" else "Independent diagnostic windows; startup, stop, sleep and scheduling gaps are not observations. JFR retains bounded tails.")}
    save_job(root, job)
    with (root / "worker.log").open("ab", buffering=0) as log:
        worker = subprocess.Popen([sys.executable, str(pathlib.Path(__file__).resolve()), "_worker", "--job", str(root)],
                                  stdin=subprocess.DEVNULL, stdout=log, stderr=log, close_fds=True, start_new_session=True)
    for _ in range(20):
        state = read_json(root / "job.json")
        if state.get("workerPid") or worker.poll() is not None:
            break
        time.sleep(.05)
    return {"jobDirectory": str(root), "workerPid": worker.pid, "status": state["status"],
            "next": ["status --job " + str(root), "collect --job " + str(root), "stop --job " + str(root)],
            "automaticAiWakeup": False, "note": "The worker survives the initiating shell. AI follow-up requires polling or a scheduler; no scheduler was created by this command."}


def window_summary(window, mode, code, requested_stop, command):
    session = session_in(window)
    result = None
    stdout = window / "stdout.json"
    if stdout.is_file() and stdout.stat().st_size:
        try:
            result = read_json(stdout)
        except (ValueError, OSError):
            pass
    if not isinstance(result, dict):
        result = None
    report = manifest = None
    if session:
        path = pathlib.Path(session)
        marker = read_json(path / "session.json")
        # The CLI streams metadata from large reports; the supervisor never loads a full trace tree.
        report = cli_json(command, ["session", "show", "--session", str(path)])
        if report.get("sessionId") != marker.get("sessionId"):
            raise ValueError("Catalog/session ID mismatch")
        source = path / "diagnostics/manifest.json"
        if source.is_file():
            manifest = read_json(safe_child(path, source))
            if manifest.get("sessionId") != marker.get("sessionId"):
                raise ValueError("Diagnostic/session ID mismatch")
        elif report.get("diagnosticSummary", {}).get("status") == "disabled":
            manifest = {"status": "disabled"}
    # Normal completion uses the explicit restore acknowledgement; SIGTERM often has no stdout.
    restored = (result or {}).get("restored") is True if mode == "capture" else None
    if mode == "capture" and requested_stop and "restored" not in (result or {}) and report and report.get("status") == "COMPLETED" and report.get("captureComplete") is True:
        restored = True  # TraceServer only writes COMPLETED after restored + drained acknowledgement.
    final_diag = bool(manifest and manifest.get("status") in {"COMPLETED", "COMPLETED_WITH_LIMITS", "DEGRADED"})
    diagnostics_usable = bool(final_diag and manifest.get("samples", 0) > 0 and not manifest.get("errors")
                              and not manifest.get("sampleLimitReached") and not manifest.get("byteLimitReached")
                              and not manifest.get("failedSampleAttempts") and not manifest.get("samplingGaps"))
    diagnostics_usable |= mode == "capture" and (manifest or {}).get("status") == "disabled"
    quality = {key: (report or {}).get(key) for key in ["status", "captureComplete", "analysisReady", "observedRootTraces", "captureQuality"]}
    quality["diagnostics"] = manifest
    accepted = (code in (0, 5) or requested_stop) and (restored if mode == "capture" else final_diag)
    return {"sessionDirectory": session, "exitCode": code, "restored": restored, "accepted": bool(accepted),
            "degraded": code == 5 or not diagnostics_usable or (mode == "capture" and (not quality["captureComplete"] or not quality["analysisReady"])),
            "quality": quality, "stdout": str(stdout), "stderr": str(window / "stderr.log")}


def summarize_final_window(window, mode, code, requested_stop, command):
    """Metadata failures must not make an already captured window disappear from collect."""
    try:
        return window_summary(window, mode, code, requested_stop, command)
    except Exception as error:
        try:
            session = session_in(window)
        except (OSError, ValueError):
            session = None
        try:
            result = read_json(window / "stdout.json")
        except (OSError, ValueError):
            result = {}
        if not isinstance(result, dict):
            result = {}
        return {"sessionDirectory": session, "exitCode": code, "restored": result.get("restored") is True if mode == "capture" else None,
                "accepted": False, "degraded": True, "quality": {}, "summaryError": str(error),
                "stdout": str(window / "stdout.json"), "stderr": str(window / "stderr.log")}


def worker_main(root):
    root = pathlib.Path(root).resolve()
    job = read_json(root / "job.json")
    if job["status"] != "pending":
        raise ValueError("A job cannot be started twice")
    stopping = [False]
    def request_stop(*_):
        stopping[0] = True
    for kind in (signal.SIGTERM, signal.SIGINT):
        signal.signal(kind, request_stop)
    job.update(status="running", workerPid=os.getpid(), startedAtMillis=now())
    deadline = time.time() + job["requestedDurationMillis"] / 1000
    save_job(root, job)
    event(root, "started", target=job["target"])
    child = None
    previous_end = None
    try:
        if job.get("nativeRolling"):
            native_worker(root,job,stopping)
            return
        while time.time() < deadline:
            if stopping[0] or (root / "stop.request").exists():
                job["status"] = "stopped"
                break
            try:
                current_identity = identity(job["target"]["pid"])
            except (OSError, ValueError, subprocess.SubprocessError):
                current_identity = None
            if current_identity != job["target"]:
                job["status"] = "target_changed"
                break
            size = directory_bytes(root)
            if size >= job["maxBytes"] or shutil.disk_usage(root).free < 128 * 1024 * 1024:
                job["status"] = "quota_reached"
                break
            remaining = deadline - time.time()
            if remaining < .5:
                break
            sequence = job["completedWindows"] + 1
            window = root / "windows" / ("%06d" % sequence)
            window.mkdir(parents=True)
            millis = max(500, min(job["windowMillis"], int(remaining * 1000)))
            args = ["capture"] if job["mode"] == "capture" else ["diag", "record"]
            args += ["--pid", str(job["target"]["pid"]), "--duration", str(millis) + "ms", "--output", str(window / "captures"), "--json"]
            if job["configuration"]:
                args += ["--config", job["configuration"]]
            start_time = now()
            current = {"sequence": sequence, "startedAtMillis": start_time, "requestedMillis": millis,
                       "gapAfterPreviousMillis": None if previous_end is None else start_time - previous_end,
                       "sessionDirectory": None}
            with (window / "stdout.json").open("wb") as out, (window / "stderr.log").open("wb") as err:
                child = subprocess.Popen(job["cliCommand"] + args, stdin=subprocess.DEVNULL, stdout=out, stderr=err, close_fds=True)
                current["cliPid"] = child.pid
                job["currentWindow"] = current
                save_job(root, job)
                event(root, "window_started", sequence=sequence, cliPid=child.pid)
                stop_started = None
                reason = None
                next_heartbeat = 0
                while child.poll() is None:
                    clock = time.time()
                    if clock >= next_heartbeat:
                        current["sessionDirectory"] = session_in(window)
                        job["bytesObserved"] = directory_bytes(root)
                        save_job(root, job)
                        next_heartbeat = clock + HEARTBEAT
                        if job["bytesObserved"] >= job["maxBytes"] or shutil.disk_usage(root).free < 128 * 1024 * 1024:
                            reason = "quota_reached"
                        try:
                            same_target = identity(job["target"]["pid"]) == job["target"]
                        except (OSError, ValueError, subprocess.SubprocessError):
                            same_target = False
                        if not same_target:
                            reason = reason or "target_changed"
                    if stopping[0] or (root / "stop.request").exists():
                        reason = reason or "stopped"
                    if clock - start_time / 1000 > millis / 1000 + 120:
                        reason = reason or "failed"
                    if reason and stop_started is None:
                        job["status"] = "stopping"
                        save_job(root, job)
                        # Only this Popen child is signalled. Never signal the business PID or a saved PID.
                        child.terminate()
                        stop_started = clock
                        event(root, "stop_sent", sequence=sequence, reason=reason)
                    if stop_started is not None and clock - stop_started > STOP_GRACE:
                        job.update(status="restore_unconfirmed" if job["mode"] == "capture" else "interrupted",
                                   attention="CLI did not exit after graceful stop; no forced kill was sent. Inspect the CLI/session and target before retrying.")
                        save_job(root, job)
                        return
                    time.sleep(.2)
                code = child.wait()
            ended = now()
            record = summarize_final_window(window, job["mode"], code, reason is not None, job["cliCommand"])
            record.update(current, endedAtMillis=ended)
            # current.sessionDirectory can lag the final filesystem; keep the final discovered path.
            if record.get("sessionDirectory") is None:
                try:
                    record["sessionDirectory"] = session_in(window)
                except (OSError, ValueError):
                    pass
            atomic_json(window / "result.json", record)
            job["completedWindows"] = sequence
            job["degradedWindows"] += int(record["degraded"])
            job["currentWindow"] = None
            event(root, "window_finished", sequence=sequence, exitCode=code, sessionDirectory=record["sessionDirectory"])
            save_job(root, job)
            previous_end = ended
            child = None
            if not record["accepted"]:
                job["status"] = "restore_unconfirmed" if job["mode"] == "capture" and not record["restored"] else "failed"
                break
            if reason:
                job["status"] = reason
                break
        if job["status"] == "running":
            job["status"] = "degraded" if job["degradedWindows"] else "completed"
    except Exception as error:
        job.update(status="failed", error=str(error))
        if child is not None and child.poll() is None:
            child.terminate()
            try:
                child.wait(timeout=STOP_GRACE)
            except subprocess.TimeoutExpired:
                pass
            if job["mode"] == "capture":
                job["status"] = "restore_unconfirmed"
    finally:
        job["finishedAtMillis"] = now()
        save_job(root, job)
        event(root, "finished", status=job["status"])


def native_worker(root,job,stopping):
    """One foreground native rolling CLI, with durable per-window consumer records."""
    directory=root/'native';directory.mkdir()
    args=['capture','--rolling','--pid',str(job['target']['pid']),'--duration',str(job['requestedDurationMillis'])+'ms',
          '--window',str(job['windowMillis'])+'ms','--max-bytes',str(job['maxBytes']),'--output',str(directory/'captures'),'--config',job['configuration'],'--json']
    if job.get('targetJvmStartedAt'):args+=['--expected-start',job['targetJvmStartedAt']]
    child=None;reason=None;stop_started=None;started=time.time();master=None
    def consume(final=False):
        nonlocal master
        master=session_in(directory)
        if not master:return
        root_session=safe_child(root,master);manifest=root_session/'rolling-capture.json'
        if not manifest.is_file():return
        archive=read_json(safe_child(root,manifest))
        for source in archive.get('windows',[]):
            number=source['sequence']
            if number<=job['completedWindows']:continue
            if number!=job['completedWindows']+1:raise ValueError('Non-contiguous native window sequence')
            session=safe_child(root,source['sessionDirectory'])
            checkpoint=None
            if source.get('checkpointRequired'):
                marker=session/'diagnostics/jfr-checkpoint.json'
                checkpoint=read_json(safe_child(root,marker)) if marker.is_file() else {'status':'PENDING'}
                if checkpoint.get('status')=='PENDING' and not final:break
                if checkpoint.get('status')=='PENDING':checkpoint={'status':'UNAVAILABLE','error':'Checkpoint did not finalize before CLI exit'}
            window=root/'windows'/('%06d'%number);window.mkdir(parents=True)
            record=dict(source,sequence=number,restored=None,requiresSessionFinalAck=True,accepted=True,degraded=bool(source.get('omittedTraceRecords')),quality={})
            try:
                summary=cli_json(job['cliCommand'],['session','show','--session',str(session)])
                record['quality']={key:summary.get(key) for key in ['status','captureComplete','analysisReady','observedRootTraces','captureQuality','diagnosticSummary']}
            except Exception as error:
                record.update(accepted=False,degraded=True,summaryError=str(error))
            record['nativeMasterSession']=master
            record['jfrCheckpoint']=checkpoint
            if checkpoint and checkpoint.get('status')!='COMPLETED':record['degraded']=True
            atomic_json(window/'result.json',record)
            job['completedWindows']=number;job['degradedWindows']+=int(record['degraded'])
            save_job(root,job);event(root,'window_finished',sequence=number,sessionDirectory=str(session),nativeRolling=True)
    try:
        try:same=identity(job['target']['pid'])==job['target']
        except (OSError,ValueError,subprocess.SubprocessError):same=False
        if not same:
            job['status']='target_changed'
            return
        with (directory/'stdout.json').open('wb') as output,(directory/'stderr.log').open('wb') as error:
            child=subprocess.Popen(job['cliCommand']+args,stdin=subprocess.DEVNULL,stdout=output,stderr=error,close_fds=True)
            job['currentWindow']={'sequence':1,'cliPid':child.pid,'sessionDirectory':None};save_job(root,job)
            next_heartbeat=0
            while child.poll() is None:
                clock=time.time()
                if clock>=next_heartbeat:
                    consume();job['currentWindow']['sessionDirectory']=master;job['currentWindow']['sequence']=job['completedWindows']+1
                    job['bytesObserved']=directory_bytes(root);save_job(root,job);next_heartbeat=clock+HEARTBEAT
                    try:same=identity(job['target']['pid'])==job['target']
                    except (OSError,ValueError,subprocess.SubprocessError):same=False
                    if not same:reason=reason or 'target_changed'
                    if job['bytesObserved']>=job['maxBytes'] or shutil.disk_usage(root).free<128*1024*1024:reason=reason or 'quota_reached'
                if stopping[0] or (root/'stop.request').exists():reason=reason or 'stopped'
                if clock-started>job['requestedDurationMillis']/1000+120:reason=reason or 'failed'
                if reason and stop_started is None:
                    job['status']='stopping';save_job(root,job);child.terminate();stop_started=clock
                if stop_started is not None and clock-stop_started>STOP_GRACE:
                    job.update(status='restore_unconfirmed',attention='Native CLI did not complete graceful stop; inspect its master session. No force kill was sent.');return
                time.sleep(.2)
        consume(True)
        summary=summarize_final_window(directory,'capture',child.returncode,reason is not None,job['cliCommand'])
        raw_result=read_json(directory/'stdout.json') if (directory/'stdout.json').stat().st_size else {}
        job['rollingArchiveError']=raw_result.get('rollingArchiveError')
        job['masterSessionDirectory']=master;job['restored']=summary['restored'];job['masterQuality']=summary['quality'];job['currentWindow']=None
        if not summary['restored']:job['status']='restore_unconfirmed'
        elif not summary['accepted']:job['status']='failed'
        elif reason:job['status']=reason
        elif (read_json(directory/'stdout.json') if (directory/'stdout.json').stat().st_size else {}).get('captureBudgetReached'):job['status']='quota_reached'
        else:job['status']='degraded' if summary['degraded'] or job['degradedWindows'] else 'completed'
    except Exception as error:
        job.update(status='failed',error=str(error))
        if child is not None and child.poll() is None:
            child.terminate()
            try:child.wait(timeout=STOP_GRACE)
            except subprocess.TimeoutExpired:job['status']='restore_unconfirmed';return
        try:
            consume()
            summary=summarize_final_window(directory,'capture',child.returncode if child else -1,True,job['cliCommand'])
            job['restored']=summary['restored']
            if not summary['restored']:job['status']='restore_unconfirmed'
        except Exception:
            job['status']='restore_unconfirmed'

def status(root):
    root = pathlib.Path(root).expanduser().resolve()
    job = read_json(root / "job.json")
    age = now() - job["heartbeatMillis"]
    job["heartbeatAgeMillis"] = age
    job["workerUnresponsive"] = job["status"] not in TERMINAL and age > 15000
    if job["workerUnresponsive"]:
        job["attention"] = "Worker heartbeat is stale. Do not restart or infer restoration; inspect worker.log, active session and CLI process."
    current = job.get("currentWindow")
    if current and current.get("sessionDirectory"):
        session = safe_child(root, current["sessionDirectory"])
        path = session / "diagnostics/manifest.json"
        if path.is_file():
            try:
                job["liveDiagnostics"] = dict(read_json(safe_child(root, path)), provisional=True)
            except (OSError, ValueError):
                pass
    return job


def consumer_path(root, consumer):
    if not re.fullmatch("[a-zA-Z0-9_-]{1,64}", consumer):
        raise ValueError("Consumer name must use 1–64 letters, digits, _ or -")
    return root / "consumers" / (consumer + ".json")


def collect(args):
    root = pathlib.Path(args.job).expanduser().resolve()
    job = status(root)
    bookmark = consumer_path(root, args.consumer)
    after = args.after if args.after is not None else (read_json(bookmark)["cursor"] if bookmark.is_file() else 0)
    if after < 0 or after > job["completedWindows"] or not 1 <= args.limit <= 50:
        raise ValueError("Cursor outside completed windows or limit outside 1–50")
    windows = []
    for sequence in range(after + 1, min(job["completedWindows"], after + args.limit) + 1):
        window = root / "windows" / ("%06d" % sequence)
        record = read_json(safe_child(root, window / "result.json"))
        session = record.get("sessionDirectory")
        if session:
            path = safe_child(root, session)
            record["analysisInputs"] = {name: str(path / name) for name in ["ai-analysis-prompt.txt", "reports/ai-analysis-output.schema.json",
                "reports/performance-report.json", "diagnostics/manifest.json", "diagnostics/latest.json", "diagnostics/threads-latest.json",
                "diagnostics/jfr-analysis.json", "diagnostics/jfr-checkpoint.json", "spans/manifest.json"] if (path / name).is_file()}
        windows.append(record)
    cursor = windows[-1]["sequence"] if windows else after
    return {"jobDirectory": str(root), "status": job["status"], "workerUnresponsive": job["workerUnresponsive"],
            "target": job["target"], "coverageNote": job["coverageNote"], "consumer": args.consumer,
            "afterCursor": after, "nextCursor": cursor, "hasMore": cursor < job["completedWindows"],
            "currentWindow": job.get("currentWindow"), "masterSessionDirectory": job.get("masterSessionDirectory"), "masterQuality": job.get("masterQuality"), "rollingArchiveError": job.get("rollingArchiveError"), "windows": windows,
            "acknowledged": False, "note": "Read and validate per-session evidence, then ack the processed cursor. Collection never advances a bookmark or uploads data."}


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="action", required=True)
    install = commands.add_parser("ensure", help="Reuse or install the standalone CLI")
    for command in [install, commands.add_parser("run", help="Execute the installed CLI")]:
        command.add_argument("--cli", help="Explicit CLI executable or fat JAR")
        command.add_argument("--install-root")
        if command is install:
            command.add_argument("--source-root")
            command.add_argument("--url")
            command.add_argument("--sha256")
            command.add_argument("--no-build", action="store_true")
        else:
            command.add_argument("arguments", nargs=argparse.REMAINDER)
    command = commands.add_parser("start", help="Start a durable segmented background capture")
    command.add_argument("--pid", type=int, required=True)
    command.add_argument("--mode", choices=["capture", "diagnostics"], default="diagnostics")
    command.add_argument("--config")
    command.add_argument("--duration", default="30m")
    command.add_argument("--window", default="5m")
    command.add_argument("--max-bytes", type=int, default=2 * 1024 * 1024 * 1024)
    command.add_argument("--work-dir", default=str(pathlib.Path.home() / ".local/state/agent-monitor/jobs"))
    command.add_argument("--cli")
    command.add_argument("--install-root")
    for name in ["status", "stop", "collect", "ack", "_worker"]:
        command = commands.add_parser(name)
        command.add_argument("--job", required=True)
        if name in ("collect", "ack"):
            command.add_argument("--consumer", default="ai")
        if name == "collect":
            command.add_argument("--after", type=int)
            command.add_argument("--limit", type=int, default=10)
        if name == "ack":
            command.add_argument("--cursor", type=int, required=True)
    return result


def main():
    args = parser().parse_args()
    try:
        if args.action == "ensure":
            emit(ensure_cli(args.cli, args.install_root, args.source_root, args.url, args.sha256, not args.no_build))
        elif args.action == "run":
            command = ensure_cli(args.cli, args.install_root)["command"]
            forwarded = args.arguments[1:] if args.arguments[:1] == ["--"] else args.arguments
            if not forwarded:
                raise ValueError("Supply CLI arguments after --")
            return subprocess.call(command + forwarded)
        elif args.action == "start":
            emit(start(args))
        elif args.action == "_worker":
            worker_main(args.job)
        elif args.action == "status":
            emit(status(args.job))
        elif args.action == "stop":
            root = pathlib.Path(args.job).expanduser().resolve()
            job = status(root)
            if job["status"] not in TERMINAL:
                atomic_json(root / "stop.request", {"requestedAtMillis": now()})
            emit({"jobDirectory": str(root), "status": job["status"], "stopRequested": job["status"] not in TERMINAL,
                  "restorationConfirmed": False, "note": "This requests graceful stop; inspect the final window acknowledgement. The business JVM is never signalled."})
        elif args.action == "collect":
            emit(collect(args))
        elif args.action == "ack":
            root = pathlib.Path(args.job).expanduser().resolve()
            job = read_json(root / "job.json")
            path = consumer_path(root, args.consumer)
            before = read_json(path)["cursor"] if path.is_file() else 0
            if not before <= args.cursor <= job["completedWindows"]:
                raise ValueError("Ack cursor must be monotonic and no greater than finalized windows")
            atomic_json(path, {"cursor": args.cursor, "acknowledgedAtMillis": now()})
            emit({"consumer": args.consumer, "cursor": args.cursor, "acknowledged": True})
        return 0
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        emit({"error": str(error)})
        return 2


if __name__ == "__main__":
    sys.exit(main())
