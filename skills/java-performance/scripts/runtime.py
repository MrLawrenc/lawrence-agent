"""Standard-library support for the portable Java performance skill (Python 3.9+)."""
import hashlib
import json
import os
import pathlib
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

MAX_JSON = 8 * 1024 * 1024
REQUIRED_HELP = ("scope preview", "session show", "ai verify", "diag record", "--rolling", "evidence query", "jvm identity", "monitor requests", "monitor diagnose", "monitor distributed")


def read_json(path, limit=MAX_JSON):
    with pathlib.Path(path).open("rb") as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError("JSON file exceeds limit: " + str(path))
    return json.loads(data)


def atomic_json(path, value):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        with temp.open("x", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def digest(path):
    result = hashlib.sha256()
    with pathlib.Path(path).open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(data)
    return result.hexdigest()


def duration(text, maximum=7 * 86400):
    match = re.fullmatch(r"([0-9]+(?:\.[0-9]+)?)(ms|s|m|h|d)", text)
    if not match:
        raise ValueError("Duration needs ms/s/m/h/d, for example 30s or 2h")
    value = float(match[1]) * {"ms": .001, "s": 1, "m": 60, "h": 3600, "d": 86400}[match[2]]
    if not .5 <= value <= maximum:
        raise ValueError("Duration outside 500ms–" + str(maximum) + "s")
    return value


def java():
    home = os.environ.get("JAVA_HOME")
    candidate = str(pathlib.Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if home else shutil.which("java")
    if not candidate or not pathlib.Path(candidate).is_file():
        raise ValueError("JDK 17+ is required. Install a local JDK and set JAVA_HOME or PATH; the skill does not install a system JDK.")
    result = subprocess.run([candidate, "-version"], capture_output=True, text=True, timeout=15)
    match = re.search(r'version "([0-9]+)', result.stdout + result.stderr)
    if result.returncode or not match or int(match[1]) < 17:
        raise ValueError("JDK 17+ is required: " + (result.stderr or result.stdout)[:500])
    return str(pathlib.Path(candidate).absolute())


def cli_command(path):
    path = pathlib.Path(path).expanduser().resolve()
    if not path.is_file():
        raise ValueError("CLI not found: " + str(path))
    if path.suffix == ".jar":
        return [java(), "--add-modules", "jdk.attach", "-jar", str(path)]
    return [str(path)]


def probe(command):
    result = subprocess.run(command + ["--help"], capture_output=True, text=True, timeout=20)
    if result.returncode or not all(token in result.stdout for token in REQUIRED_HELP):
        raise ValueError("CLI is incompatible: rolling capture and evidence queries are required; upgrade from the project or a pinned release")
    return result.stdout


def source_repository(selected=None):
    if selected:
        candidates = [pathlib.Path(selected).expanduser().resolve()]
    else:
        candidates = list(pathlib.Path(__file__).resolve().parents)
    return next((p for p in candidates if (p / "monitor-cli/build.gradle").is_file() and (p / "gradlew").is_file()), None)


def ensure_cli(cli=None, install_root=None, source_root=None, url=None, sha256=None, build=True):
    """Reuse an installed CLI or install a verified local/release fat JAR, never the GUI."""
    explicit = cli or os.environ.get("MONITOR_CLI")
    root = pathlib.Path(install_root or os.environ.get("MONITOR_SKILL_HOME", pathlib.Path.home() / ".local/share/agent-monitor")).expanduser().resolve()
    if url and (not sha256 or not re.fullmatch("[0-9a-fA-F]{64}", sha256)):
        raise ValueError("Remote CLI installation requires an explicit SHA-256 digest")
    # An explicit source/URL is a request to install that artifact, rather than reuse PATH.
    if explicit and pathlib.Path(explicit).suffix != ".jar":
        command = cli_command(explicit)
        probe(command)
        return {"command": command, "source": "explicit-executable", "managed": False}
    if not explicit and not url and not source_root:
        manifest = root / "install.json"
        if manifest.is_file():
            installed = read_json(manifest)
            jar = pathlib.Path(installed["jar"])
            if jar.is_file() and digest(jar) == installed["sha256"]:
                command = cli_command(jar)
                try:
                    probe(command)
                    return dict(installed, command=command, source="managed-cache", managed=True)
                except ValueError:
                    if source_repository() is None:
                        raise ValueError("Installed CLI needs upgrading; supply --source-root or a compatible pinned JAR")
                    print("Upgrading the cached CLI from this project…",file=sys.stderr)
            else:
                raise ValueError("Managed CLI failed integrity check; reinstall from a trusted artifact")
        found = shutil.which("monitor")
        if found:
            try:
                command = cli_command(found)
                probe(command)
                return {"command": command, "source": "PATH", "managed": False}
            except (ValueError, OSError, subprocess.SubprocessError):
                pass
    repository = source_repository(source_root)
    temporary = None
    try:
        if explicit:
            artifact = pathlib.Path(explicit).expanduser().resolve()
            origin = "local-jar"
        elif url:
            if not url.startswith("https://"):
                raise ValueError("CLI download requires HTTPS")
            root.mkdir(parents=True, exist_ok=True)
            fd, name = tempfile.mkstemp(prefix="download-", suffix=".jar", dir=root)
            os.close(fd)
            temporary = pathlib.Path(name)
            with urllib.request.urlopen(url, timeout=30) as response, temporary.open("wb") as output:
                if not response.geturl().startswith("https://"):
                    raise ValueError("CLI redirect must remain HTTPS")
                size = 0
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > 128 * 1024 * 1024:
                        raise ValueError("CLI download exceeds 128MiB")
                    output.write(chunk)
            artifact, origin = temporary, url
        elif repository:
            artifact = repository / "monitor-cli/build/libs/monitor-cli-all.jar"
            compatible=False
            if artifact.is_file():
                try:probe(cli_command(artifact));compatible=True
                except ValueError:pass
            if not compatible:
                if not build:
                    raise ValueError("Build CLI first: ./gradlew :monitor-cli:shadowJar")
                wrapper = str(repository / ("gradlew.bat" if os.name == "nt" else "gradlew"))
                java()
                print("Building the CLI from the selected repository…", file=sys.stderr)
                result = subprocess.run([wrapper, "--console=plain", ":monitor-cli:shadowJar"], cwd=repository,
                                        stdout=sys.stderr, stderr=sys.stderr, timeout=600)
                if result.returncode:
                    raise ValueError("CLI build failed; no CLI was installed")
            origin = str(repository)
        else:
            raise ValueError("CLI is not installed. Supply --cli /path/monitor-cli-all.jar, --source-root /path/to/project, or --url HTTPS_URL --sha256 DIGEST. No public release URL is configured.")
        if not artifact.is_file() or artifact.stat().st_size > 128 * 1024 * 1024:
            raise ValueError("CLI JAR is missing or exceeds 128MiB")
        actual = digest(artifact)
        if sha256 and actual != sha256.lower():
            raise ValueError("CLI SHA-256 mismatch; refusing to install or execute")
        destination = root / "runtimes" / (actual + ".jar")
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists():
            pending = destination.with_name(actual + "." + uuid.uuid4().hex + ".tmp")
            try:
                shutil.copyfile(artifact, pending)
                if digest(pending) != actual:
                    raise ValueError("CLI changed while copying")
                os.replace(pending, destination)
            finally:
                pending.unlink(missing_ok=True)
        if digest(destination) != actual:
            raise ValueError("Cached CLI integrity mismatch")
        command = cli_command(destination)
        probe(command)
        binary = root / "bin" / ("monitor.cmd" if os.name == "nt" else "monitor")
        binary.parent.mkdir(parents=True, exist_ok=True)
        if os.name == "nt":
            binary.write_text('@echo off\r\njava --add-modules jdk.attach -jar "' + str(destination) + '" %*\r\n', encoding="utf-8")
        else:
            binary.write_text("#!/bin/sh\nexec " + " ".join(shlex.quote(s) for s in command) + ' "$@"\n', encoding="utf-8")
            binary.chmod(0o755)
        installed = {"jar": str(destination), "sha256": actual, "origin": origin, "launcher": str(binary), "installedAtMillis": int(time.time() * 1000)}
        atomic_json(root / "install.json", installed)
        return dict(installed, command=command, source="installed", managed=True)
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)


def identity(pid):
    """PID plus an OS process-start token. Never use a bare PID across windows."""
    pid = int(pid)
    if pid <= 0 or pid == os.getpid():
        raise ValueError("An external positive JVM PID is required")
    if sys.platform.startswith("linux"):
        stat = pathlib.Path("/proc") / str(pid) / "stat"
        fields = stat.read_text().rsplit(")", 1)[1].split()
        return {"pid": pid, "platform": "linux", "startToken": pathlib.Path("/proc/sys/kernel/random/boot_id").read_text().strip() + ":" + fields[19]}
    if sys.platform == "darwin":
        result = subprocess.run(["ps", "-p", str(pid), "-o", "lstart=", "-o", "comm="], capture_output=True, text=True, timeout=5)
        if result.returncode or not result.stdout.strip():
            raise ValueError("Target process is not alive or its start identity cannot be read")
        return {"pid": pid, "platform": "darwin", "startToken": result.stdout.strip()}
    raise ValueError("Detached capture currently supports macOS and Linux; use the foreground CLI on Windows")


def cli_json(command, args, timeout=30):
    result = subprocess.run(command + args + ["--json"], capture_output=True, text=True, timeout=timeout)
    if len(result.stdout.encode("utf-8")) > MAX_JSON:
        raise ValueError("CLI response exceeds 8MiB")
    document = json.loads(result.stdout)
    if result.returncode:
        raise ValueError("CLI failed (" + str(result.returncode) + "): " + result.stderr[-2000:] + " " + str(document)[:1000])
    return document
