"""Build replay tools from current sources with bounded diagnostics and provenance."""
import glob
import hashlib
import os
import signal
from pathlib import Path
import subprocess
import threading

REPLAY_SOURCES = (
    "core/src/main/kotlin/app/focal/core/SpeechGate.kt",
    "core/src/main/kotlin/app/focal/core/Transcript.kt",
    "scripts/SpeechGateReplay.kt",
    "scripts/TranscriptReplay.kt",
)


def digest_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(65_536), b""):
            digest.update(block)
    return digest.hexdigest()


def bounded_process(command, timeout, limit=128 * 1024, *, include_stderr=False):
    """Bound both pipe buffers and terminate the subprocess tree on failure."""
    options = {"start_new_session": True} if os.name != "nt" else {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **options)
    output = [bytearray(), bytearray()]
    overflow = threading.Event()
    def kill_tree():
        if os.name == "nt":
            try:
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2, check=False)
            except (OSError, subprocess.TimeoutExpired):
                pass
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        try:
            process.kill()
        except OSError:
            pass
    def drain(index, stream):
        try:
            while block := stream.read(4096):
                room = limit - len(output[index])
                output[index].extend(block[:max(room, 0)])
                if len(block) > room:
                    overflow.set()
                    kill_tree()
                    break
        finally:
            stream.close()
    threads = [threading.Thread(target=drain, args=(i, stream), daemon=True)
               for i, stream in enumerate((process.stdout, process.stderr))]
    for thread in threads:
        thread.start()
    timed_out = False
    interruption = None
    try:
        code = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        kill_tree()
        code = process.wait(timeout=2)
    except BaseException as error:
        interruption = error
        kill_tree()
        code = process.wait(timeout=2)
    # Descendants can inherit pipes even after the parent exits. Never join a
    # reader indefinitely; stop the remaining process group before retrying.
    for thread in threads:
        thread.join(timeout=1)
    if any(thread.is_alive() for thread in threads):
        kill_tree()
        for thread in threads:
            thread.join(timeout=1)
    if any(thread.is_alive() for thread in threads):
        raise ValueError("Replay subprocess left a diagnostic pipe open")
    if interruption is not None:
        raise interruption
    if timed_out:
        raise subprocess.TimeoutExpired(command, timeout)
    if overflow.is_set():
        raise ValueError("Replay or compiler exceeded its diagnostic output limit")
    if code:
        raise ValueError("Replay or compiler failed; check the cached tools and inputs")
    result = bytes(output[0]) + (bytes(output[1]) if include_stderr else b"")
    return result.decode("utf-8", errors="strict")


def compiler_dependencies(classpath_file):
    path = Path(classpath_file)
    if path.stat().st_size > 64 * 1024:
        raise ValueError("Compiler classpath file is too large")
    value = path.read_text(encoding="utf-8").strip()
    result = []
    for item in value.split(os.pathsep):
        if not item:
            raise ValueError("Compiler classpath must not contain empty entries")
        if item.endswith("*"):
            result.extend(Path(p).resolve() for p in sorted(glob.glob(item + ".jar")))
        else:
            result.append(Path(item).resolve())
    if not 1 <= len(result) <= 256 or any(not path.is_file() or path.suffix != ".jar" for path in result):
        raise ValueError("Choose an explicit cached Kotlin compiler jar classpath")
    return result


def compile_replay(repository, java, compiler_file, stdlib, classes, timeout):
    repository, classes, stdlib = Path(repository).resolve(), Path(classes), Path(stdlib).resolve()
    dependencies = compiler_dependencies(compiler_file)
    if not stdlib.is_file() or stdlib.suffix != ".jar":
        raise ValueError("Choose a cached Kotlin standard-library jar")
    sources = [repository / name for name in REPLAY_SOURCES]
    source_hashes = {name: digest_file(path) for name, path in zip(REPLAY_SOURCES, sources)}
    dependencies_hashes = [digest_file(path) for path in dependencies]
    stdlib_hash = digest_file(stdlib)
    classes.mkdir(parents=True, exist_ok=False)
    compiler = [java, "-Xmx512m", "-XX:ActiveProcessorCount=1", "-cp", os.pathsep.join(map(str, dependencies)),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"]
    compiler_version = bounded_process(compiler + ["-version"], timeout, limit=256 * 1024, include_stderr=True).strip()
    command = compiler + ["-no-stdlib", "-no-reflect", "-classpath", str(stdlib),
               *map(str, sources), "-d", str(classes)]
    bounded_process(command, timeout, limit=256 * 1024)
    if source_hashes != {name: digest_file(path) for name, path in zip(REPLAY_SOURCES, sources)}:
        raise ValueError("Replay sources changed during compilation")
    class_files = sorted(path for path in classes.rglob("*") if path.is_file())
    if not class_files or len(class_files) > 256:
        raise ValueError("Compiler did not produce a bounded replay class tree")
    if dependencies_hashes != [digest_file(path) for path in dependencies] or stdlib_hash != digest_file(stdlib):
        raise ValueError("Compiler or runtime changed during replay compilation")
    return dict(sources=source_hashes,
                compiler_version=compiler_version, compiler_sha256=dependencies_hashes, stdlib_sha256=stdlib_hash,
                classes={str(path.relative_to(classes)).replace(os.sep, "/"): digest_file(path) for path in class_files})
