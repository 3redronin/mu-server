"""Build identity, bounded child lifetime, and the fixture control channel."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "target"

def sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""): h.update(chunk)
    return h.hexdigest()

def identity(checkout):
    checkout = Path(checkout).resolve()
    def git(*args):
        return subprocess.check_output(["git", "-C", str(checkout), *args], text=True).strip()
    # Includes tracked modifications and untracked source, but never build output.
    digest = hashlib.sha256()
    files = subprocess.check_output(["git", "-C", str(checkout), "ls-files", "-z", "--cached", "--others", "--exclude-standard"])
    for name in sorted(set(files.split(b"\0")) - {b""}):
        if name != b"pom.xml" and not name.startswith(b"src/main/"):
            continue
        path = checkout / os.fsdecode(name)
        if path.is_file(): digest.update(name + b"\0" + bytes.fromhex(sha256(path)))
    return {"path": str(checkout), "commit": git("rev-parse", "HEAD"), "dirty": bool(git("status", "--porcelain")),
            "source_sha256": digest.hexdigest()}

def jdk_path(version):
    override = os.environ.get(f"MU_JAVA_{version}")
    if override: return Path(override).resolve()
    path = Path.home() / ".local/share/mise/installs/java" / f"temurin-{version}"
    if path.exists(): return path.resolve()
    raise RuntimeError(f"Set MU_JAVA_{version} to a JDK {version} installation")

def environment(jdk):
    env = os.environ.copy()
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env["PATH"]
    return env

def run_logged(command, log, *, cwd=None, env=None, timeout=600):
    log = Path(log); log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("wb") as output:
        process = subprocess.Popen(list(map(str, command)), cwd=cwd, env=env, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try: code = process.wait(timeout=timeout)
        except BaseException:
            os.killpg(process.pid, signal.SIGKILL); process.wait(); raise
    if code: raise RuntimeError(f"Command exited {code}; see {log}")

def build(name, checkout, jdk, verify=False):
    directory = TARGET / "build" / name
    directory.mkdir(parents=True, exist_ok=True)
    classpath = Path(checkout) / "target/validation-classpath.txt"
    command = ["mvn", "--batch-mode", "--no-transfer-progress"]
    if name != "mu4": command += ["-Pnetty-4.1"]
    if verify:
        if "21" in str(jdk): command += ["-Pnullaway"]
        command += ["verify"]
    else: command += ["-DskipTests", "test-compile"]
    command += ["org.apache.maven.plugins:maven-dependency-plugin:3.8.0:build-classpath",
                f"-Dmdep.outputFile={classpath}"]
    print(f"Building {name} ({jdk.name})", flush=True)
    run_logged(command, directory / "maven.log", cwd=checkout, env=environment(jdk), timeout=900)
    cp = str(Path(checkout).resolve() / "target/classes") + os.pathsep + classpath.read_text().strip()
    classes = directory / "classes"; classes.mkdir(exist_ok=True)
    adapter = ROOT / "fixture" / ("mu4" if name == "mu4" else "legacy") / "EchoSocket.java"
    run_logged([jdk / "bin/javac", "--release", "11", "-cp", cp, "-d", classes,
                ROOT / "fixture/Fixture.java", ROOT / "fixture/IndependentClient.java", adapter], directory / "javac.log")
    metadata = {"name": name, "source": identity(checkout), "classpath": str(classes) + os.pathsep + cp,
                "fixture_sources": {str(p): sha256(p) for p in (ROOT/"fixture/Fixture.java", ROOT/"fixture/IndependentClient.java", adapter)},
                "java": subprocess.check_output([str(jdk / "bin/java"), "-version"], stderr=subprocess.STDOUT, text=True),
                "dependencies": {p: sha256(p) for p in cp.split(os.pathsep) if Path(p).is_file()}}
    # Hash exactly the compiled bytecode that is about to be exercised.
    metadata["classes"] = {str(p): sha256(p) for base in (classes, Path(checkout) / "target/classes") for p in sorted(base.rglob("*.class"))}
    (directory / "build.json").write_text(json.dumps(metadata, indent=2))
    return metadata

def load_build(name):
    metadata = json.loads((TARGET / "build" / name / "build.json").read_text())
    if identity(metadata["source"]["path"]) != metadata["source"]:
        raise RuntimeError(f"{name} sources changed: run build again")
    for group in ("classes", "dependencies", "fixture_sources"):
        for path, digest in metadata[group].items():
            if not Path(path).exists() or sha256(path) != digest: raise RuntimeError(f"Stale build: {path}")
    return metadata

class Fixture:
    def __init__(self, name, jdk, output, configuration="matched", proxy=False, keystore=None, heap="4g", cpus=None, client_ca=None, client_auth=None):
        self.metadata = load_build(name)
        self.name, self.jdk = name, Path(jdk)
        self.directory = Path(output); self.directory.mkdir(parents=True, exist_ok=True)
        uploads = self.directory / "uploads"; uploads.mkdir(exist_ok=True)
        command = [str(self.jdk / "bin/java"), f"-Xmx{heap}", f"-Djava.io.tmpdir={uploads}", "-XX:NativeMemoryTracking=summary",
                   "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", self.metadata["classpath"],
                   "validation.Fixture", f"uploads={uploads}", f"configuration={configuration}", f"proxy={str(proxy).lower()}"]
        if keystore: command.append(f"keystore={keystore}")
        if client_ca: command.append(f"client-ca={client_ca}")
        if client_auth: command.append(f"client-auth={client_auth}")
        if cpus: command = ["taskset", "-c", cpus, *command]
        self.log = (self.directory / "server.log").open("w")
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log,
                                        text=True, bufsize=1, start_new_session=True)
        self.lines = queue.Queue()
        def reader():
            for line in self.process.stdout:
                self.log.write(line); self.log.flush(); self.lines.put(line.strip())
            self.lines.put("EXIT")
        self.reader = threading.Thread(target=reader, daemon=True); self.reader.start()
        self.lock = threading.Lock()
        try:
            ready = self._line("READY", 30).split()
            self.http, self.https = int(ready[1]), int(ready[2])
        except BaseException: self.close(); raise
        (self.directory / "fixture.json").write_text(json.dumps({"build": self.metadata, "command": command,
                                                               "pid": self.process.pid}, indent=2))

    def _line(self, prefix, timeout=5):
        deadline = time.monotonic() + timeout
        while True:
            try: line = self.lines.get(timeout=max(0, deadline-time.monotonic()))
            except queue.Empty: raise TimeoutError(f"Fixture did not emit {prefix}; {self.directory}")
            if line.startswith(prefix): return line
            if line == "EXIT": raise RuntimeError(f"Fixture exited: {self.directory}")

    def control(self, command, expected, timeout=5):
        with self.lock:
            self.process.stdin.write(command + "\n"); self.process.stdin.flush()
            return self._line(expected, timeout)

    def stats(self):
        values = self.control("STATS", "STATS").split()[1:]
        result = dict(zip(("handled", "completed", "failed", "heap_bytes", "platform_threads", "buffer_pool_bytes"), map(int, values)))
        proc = Path("/proc") / str(self.process.pid)
        if proc.exists():
            for line in (proc / "status").read_text().splitlines():
                if line.startswith("VmRSS:"): result["rss_bytes"] = int(line.split()[1])*1024
            result["fds"] = len(list((proc / "fd").iterdir()))
            fields = (proc / "stat").read_text().rsplit(")", 1)[1].split()
            result["cpu_seconds"] = (int(fields[11])+int(fields[12])) / os.sysconf("SC_CLK_TCK")
        result["time"] = time.time()
        return result

    def diagnostics(self):
        for label, args in (("threads", ["Thread.print"]), ("native", ["VM.native_memory", "summary"]),
                            ("heap", ["GC.heap_info"])):
            try: run_logged([self.jdk / "bin/jcmd", str(self.process.pid), *args], self.directory / f"{label}.txt", timeout=10)
            except (OSError, RuntimeError, subprocess.TimeoutExpired): pass

    def close(self):
        if self.process.poll() is None:
            try: self.control("STOP 2000", "STOPPED", 5); self.process.wait(timeout=5)
            except (OSError, RuntimeError, TimeoutError, subprocess.TimeoutExpired):
                self.diagnostics()
                os.killpg(self.process.pid, signal.SIGKILL); self.process.wait()
        self.reader.join(timeout=2)
        for stream in (self.process.stdin,self.process.stdout):
            if stream:
                try: stream.close()
                except (OSError,ValueError): pass
        self.log.close()

    def __enter__(self): return self
    def __exit__(self, *args): self.close()
