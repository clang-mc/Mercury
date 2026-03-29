import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
RUN_DIR = ROOT / "run"
WORLD_DIR = RUN_DIR / "world"
DATAPACKS_DIR = WORLD_DIR / "datapacks"
SOURCE_DATAPACK = DATAPACKS_DIR / "mercury_demo"
TARGET_DATAPACK = DATAPACKS_DIR / "mercury_demo_testcopy"
LOG_PATH = ROOT / "integration-test-server.log"
RCON_HOST = "127.0.0.1"
RCON_PORT = 25575
RCON_PASSWORD = "mercury_test"


def rcon_packet(request_id: int, packet_type: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, packet_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def rcon_recv(sock: socket.socket) -> tuple[int, int, str]:
    header = sock.recv(4)
    if not header:
        raise RuntimeError("No RCON response received")
    (length,) = struct.unpack("<i", header)
    data = sock.recv(length)
    request_id, packet_type = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", errors="replace")
    return request_id, packet_type, body


class RconClient:
    def __init__(self, host: str, port: int, password: str):
        self.host = host
        self.port = port
        self.password = password
        self.sock: socket.socket | None = None
        self.next_id = 1

    def connect(self) -> None:
        self.sock = socket.create_connection((self.host, self.port), timeout=10)
        self.sock.sendall(rcon_packet(self.next_id, 3, self.password))
        self.next_id += 1
        rcon_recv(self.sock)

    def command(self, body: str) -> str:
        assert self.sock is not None
        self.sock.sendall(rcon_packet(self.next_id, 2, body))
        self.next_id += 1
        _, _, response = rcon_recv(self.sock)
        return response

    def close(self) -> None:
        if self.sock is not None:
            self.sock.close()
            self.sock = None


def prepare_datapack_copy() -> None:
    if TARGET_DATAPACK.exists():
        shutil.rmtree(TARGET_DATAPACK)
    shutil.copytree(SOURCE_DATAPACK, TARGET_DATAPACK)


def start_server() -> subprocess.Popen[str]:
    if LOG_PATH.exists():
        try:
            LOG_PATH.unlink()
        except PermissionError:
            fallback = LOG_PATH.with_name(f"{LOG_PATH.stem}-{int(time.time())}{LOG_PATH.suffix}")
            if fallback.exists():
                fallback.unlink()
            LOG_PATH.replace(fallback)
    log_handle = LOG_PATH.open("w", encoding="utf-8")
    return subprocess.Popen(
        ["cmd.exe", "/c", ".\\gradlew.bat runServer --console=plain"],
        cwd=ROOT,
        stdout=log_handle,
        stderr=subprocess.STDOUT,
        text=True,
    )


def wait_for_rcon(timeout_seconds: float = 120.0) -> None:
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        if LOG_PATH.exists():
            text = LOG_PATH.read_text(encoding="utf-8", errors="replace")
            if "RCON running on" in text:
                return
        time.sleep(1.0)
    raise TimeoutError("Timed out waiting for server RCON startup")


def read_dump_files(root: Path) -> dict[str, str]:
    results: dict[str, str] = {}
    for path in root.rglob("*.txt"):
        results[str(path)] = path.read_text(encoding="utf-8", errors="replace")
    return results


def require_dump_containing(files: dict[str, str], needle: str) -> tuple[str, str]:
    for path, text in files.items():
        if needle in text:
            return path, text
    raise AssertionError(f"Expected dump containing {needle!r}")


def forbid_dump_containing(files: dict[str, str], needle: str) -> None:
    for path, text in files.items():
        if needle in text:
            raise AssertionError(f"Unexpected dump containing {needle!r}: {path}")


def require_positive_counter(text: str, label: str) -> None:
    token = f"{label}="
    start = text.find(token)
    if start < 0:
        raise AssertionError(f"Missing counter {label!r} in {text!r}")
    start += len(token)
    end = start
    while end < len(text) and text[end].isdigit():
        end += 1
    value = int(text[start:end])
    if value <= 0:
        raise AssertionError(f"Expected positive counter {label!r}, got {value} in {text!r}")


def run_command(client: RconClient, command: str, expected: str | None = None) -> str:
    response = client.command(command)
    print(f"$ {command}")
    print(response)
    if expected is not None and expected not in response:
        raise AssertionError(f"Unexpected response for {command!r}: {response!r}")
    return response


def run_hot_macro_sequence(client: RconClient, function_id: str, values: list[int]) -> None:
    for value in values:
        run_command(client, f"scoreboard players set input mercury_macro_input {value}")
        run_command(client, f"function {function_id}", f"Running function {function_id}")


def run_assertions(client: RconClient) -> None:
    run_command(client, "reload", "Reloading!")
    run_command(client, "scoreboard objectives add mercury_exec dummy")
    run_command(client, "scoreboard objectives add mercury_inline dummy")
    run_command(client, "scoreboard objectives add mercury_macro dummy")
    run_command(client, "scoreboard objectives add mercury_macro_input dummy")
    run_command(client, "function demo:execute_if_score_set", "Running function demo:execute_if_score_set")
    run_command(client, "scoreboard players get out mercury_exec", "out has 11 [mercury_exec]")
    run_command(client, "function demo:execute_store_score_get", "Running function demo:execute_store_score_get")
    run_command(client, "scoreboard players get stored mercury_exec", "stored has 23 [mercury_exec]")
    run_command(client, "function demo:execute_store_storage_get", "Running function demo:execute_store_storage_get")
    run_command(client, "data get storage demo:test payload", "Storage demo:test has the following contents: {value: 17}")
    run_command(client, "function demo:inline_entry", "Running function demo:inline_entry")
    run_command(client, "scoreboard players get alpha mercury_inline", "alpha has 9 [mercury_inline]")

    run_command(client, "scoreboard players set input mercury_macro_input 7")
    for _ in range(6):
        run_command(client, "function demo:macro_call_storage", "Running function demo:macro_call_storage")
    run_command(client, "scoreboard players get alpha mercury_macro", "alpha has 7 [mercury_macro]")

    run_hot_macro_sequence(client, "demo:macro_call_storage_hot", [1, 2, 3, 4] * 25)
    run_command(client, "scoreboard players set input mercury_macro_input 3")
    run_command(client, "function demo:macro_call_storage_hot", "Running function demo:macro_call_storage_hot")
    run_command(client, "scoreboard players get alpha mercury_macro", "alpha has 3 [mercury_macro]")

    run_hot_macro_sequence(client, "demo:macro_call_storage_tier2_growth", [1] * 50 + [2] * 50)
    run_hot_macro_sequence(client, "demo:macro_call_storage_tier2_growth", [3] * 6)
    run_command(client, "scoreboard players get alpha mercury_macro", "alpha has 3 [mercury_macro]")

    run_hot_macro_sequence(client, "demo:macro_call_storage_large_hot", [7] * 80 + [8] * 20)
    run_command(client, "scoreboard players set input mercury_macro_input 7")
    run_command(client, "function demo:macro_call_storage_large_hot", "Running function demo:macro_call_storage_large_hot")
    run_command(client, "scoreboard players get alpha mercury_macro", "alpha has 7 [mercury_macro]")

    run_hot_macro_sequence(client, "demo:macro_call_storage_unstable", [1, 2, 3, 4])
    run_command(client, "scoreboard players get alpha mercury_macro", "alpha has 4 [mercury_macro]")

    run_command(client, "mercury dump prefetch", "Exported prefetch")
    run_command(client, "mercury dump macro-optimization", "Exported macroProfile=")
    run_command(client, "mercury dump classes", "Exported ")

    class_dir = RUN_DIR / "mercury" / "dumped" / "classes" / "demo"
    if not (class_dir / "execute_if_score_set.class").exists():
        raise AssertionError("Missing execute_if_score_set.class export")
    if not (class_dir / "execute_store_score_get.class").exists():
        raise AssertionError("Missing execute_store_score_get.class export")
    if not (class_dir / "execute_store_storage_get.class").exists():
        raise AssertionError("Missing execute_store_storage_get.class export")
    if not (class_dir / "inline_entry.class").exists():
        raise AssertionError("Missing inline_entry.class export")
    if not (class_dir / "macro_call_storage.class").exists():
        raise AssertionError("Missing macro_call_storage.class export")
    if not (class_dir / "macro_call_storage_hot.class").exists():
        raise AssertionError("Missing macro_call_storage_hot.class export")
    if not (class_dir / "macro_call_storage_tier2_growth.class").exists():
        raise AssertionError("Missing macro_call_storage_tier2_growth.class export")
    if not (class_dir / "macro_call_storage_unstable.class").exists():
        raise AssertionError("Missing macro_call_storage_unstable.class export")
    if not (class_dir / "macro_call_storage_large_hot.class").exists():
        raise AssertionError("Missing macro_call_storage_large_hot.class export")
    if (class_dir / "inline_mid.class").exists():
        raise AssertionError("inline_mid.class should have been inlined away")
    if (class_dir / "inline_leaf.class").exists():
        raise AssertionError("inline_leaf.class should have been inlined away")
    if (class_dir / "scoreboard_add.class").exists():
        raise AssertionError("scoreboard_add.class should have been inlined away")

    macro_class_bytes = (class_dir / "macro_call_storage.class").read_bytes()
    if b"invokePrefetchedMacro" not in macro_class_bytes:
        raise AssertionError("macro_call_storage.class does not reference invokePrefetchedMacro")
    if b"prefetch" not in macro_class_bytes:
        raise AssertionError("macro_call_storage.class does not reference prefetch")
    hot_class_bytes = (class_dir / "macro_call_storage_hot.class").read_bytes()
    if b"loadArgumentsForTier2" not in hot_class_bytes:
        raise AssertionError("macro_call_storage_hot.class was not rebuilt into a tier2 guarded macro dispatch")
    if b"invokePrefetchedMacro" in hot_class_bytes:
        raise AssertionError("macro_call_storage_hot.class should no longer reference tier1 macro invocation after tier2 install")
    growth_class_bytes = (class_dir / "macro_call_storage_tier2_growth.class").read_bytes()
    if b"loadArgumentsForTier2" not in growth_class_bytes:
        raise AssertionError("macro_call_storage_tier2_growth.class was not rebuilt into a tier2 guarded macro dispatch")
    unstable_class_bytes = (class_dir / "macro_call_storage_unstable.class").read_bytes()
    if b"invokePrefetchedMacro" not in unstable_class_bytes or b"prefetch" not in unstable_class_bytes:
        raise AssertionError("macro_call_storage_unstable.class does not contain prefetch macro calls")
    large_hot_class_bytes = (class_dir / "macro_call_storage_large_hot.class").read_bytes()
    if b"loadArgumentsForTier2" not in large_hot_class_bytes:
        raise AssertionError("macro_call_storage_large_hot.class was not rebuilt into a tier2 guarded macro dispatch")

    prefetch_root = RUN_DIR / "mercury" / "dumped" / "prefetch"
    if not (prefetch_root / "stats.txt").exists():
        raise AssertionError("Missing prefetch stats dump")
    active_dir = prefetch_root / "active"
    active_files = list(active_dir.rglob("*.txt"))
    if not active_files:
        raise AssertionError("Expected at least one active prefetch dump file")
    stats_text = (prefetch_root / "stats.txt").read_text(encoding="utf-8", errors="replace")
    if "active=0" in stats_text:
        raise AssertionError(f"Expected active prefetch plans, got stats: {stats_text!r}")

    macro_optimization_root = RUN_DIR / "mercury" / "dumped" / "macro-optimization"
    if not (macro_optimization_root / "macro-profile").exists():
        raise AssertionError("Missing macro-profile dump directory")
    if not (macro_optimization_root / "macro-specialization").exists():
        raise AssertionError("Missing macro-specialization dump directory")
    if not (macro_optimization_root / "tier2").exists():
        raise AssertionError("Missing tier2 dump directory")
    if not list((macro_optimization_root / "macro-profile").glob("*.txt")):
        raise AssertionError("Expected macro-profile dump files")
    if not list((macro_optimization_root / "macro-specialization").glob("*.txt")):
        raise AssertionError("Expected macro-specialization dump files")

    profile_files = read_dump_files(macro_optimization_root / "macro-profile")
    specialization_files = read_dump_files(macro_optimization_root / "macro-specialization")
    tier2_files = read_dump_files(macro_optimization_root / "tier2")

    hot_profile_path, hot_profile = require_dump_containing(profile_files, "callerFunctionId=demo:macro_call_storage_hot")
    if "totalCalls=100" not in hot_profile:
        raise AssertionError(f"Expected hot profile to stop growing on tier2 guard hits, got: {hot_profile_path} -> {hot_profile!r}")
    require_positive_counter(hot_profile, "guardHits")
    require_positive_counter(hot_profile, "guardMisses")
    require_positive_counter(hot_profile, "specializationUses")
    require_positive_counter(hot_profile, "fallbackUses")
    for expected_value in ("value=1", "value=2", "value=3", "value=4"):
        if expected_value not in hot_profile:
            raise AssertionError(f"Expected hot profile candidate guard for {expected_value}: {hot_profile_path} -> {hot_profile!r}")

    unstable_profile_path, unstable_profile = require_dump_containing(profile_files, "callerFunctionId=demo:macro_call_storage_unstable")
    if "totalCalls=4" not in unstable_profile:
        raise AssertionError(f"Expected 4 unstable macro calls, got: {unstable_profile_path} -> {unstable_profile!r}")
    if "candidate guard=" in unstable_profile:
        raise AssertionError(f"Unstable callsite should not produce a specialization candidate: {unstable_profile_path} -> {unstable_profile!r}")
    if "specializationUses=0" not in unstable_profile:
        raise AssertionError(f"Unstable callsite should not use specialization: {unstable_profile_path} -> {unstable_profile!r}")

    hot_specializations = [
        (path, text) for path, text in specialization_files.items() if "callerFunctionId=demo:macro_call_storage_hot" in text
    ]
    if len(hot_specializations) < 4:
        raise AssertionError(f"Expected four installed specializations for small hot macro, got {len(hot_specializations)}")
    for expected_value in ("value=1", "value=2", "value=3", "value=4"):
        if not any(expected_value in text for _, text in hot_specializations):
            raise AssertionError(f"Expected installed hot specialization for {expected_value}")

    growth_specializations = [
        (path, text) for path, text in specialization_files.items() if "callerFunctionId=demo:macro_call_storage_tier2_growth" in text
    ]
    if len(growth_specializations) < 3:
        raise AssertionError(f"Expected post-tier2 growth callsite to install a third specialization, got {len(growth_specializations)}")
    for expected_value in ("value=1", "value=2", "value=3"):
        if not any(expected_value in text for _, text in growth_specializations):
            raise AssertionError(f"Expected growth specialization for {expected_value}")

    growth_profile_path, growth_profile = require_dump_containing(profile_files, "callerFunctionId=demo:macro_call_storage_tier2_growth")
    if "totalCalls=106" not in growth_profile:
        raise AssertionError(f"Expected growth profile to continue after tier2 and record new fallback values, got: {growth_profile_path} -> {growth_profile!r}")
    if "value=3" not in growth_profile:
        raise AssertionError(f"Expected growth profile to observe post-tier2 fallback value=3: {growth_profile_path} -> {growth_profile!r}")

    large_profile_path, large_profile = require_dump_containing(profile_files, "callerFunctionId=demo:macro_call_storage_large_hot")
    if "totalCalls=100" not in large_profile:
        raise AssertionError(f"Expected large macro profile to stop growing on tier2 guard hits, got: {large_profile_path} -> {large_profile!r}")
    if 'value=7' not in large_profile:
        raise AssertionError(f"Expected dominant value=7 in large macro profile: {large_profile_path} -> {large_profile!r}")

    large_specializations = [
        (path, text) for path, text in specialization_files.items() if "callerFunctionId=demo:macro_call_storage_large_hot" in text
    ]
    if len(large_specializations) != 1:
        raise AssertionError(f"Expected exactly one installed specialization for large hot macro, got {len(large_specializations)}")
    if 'value=7' not in large_specializations[0][1]:
        raise AssertionError(f"Expected large hot specialization to guard on dominant value=7: {large_specializations[0]}")

    forbid_dump_containing(specialization_files, "callerFunctionId=demo:macro_call_storage_unstable")

    hot_tier2_path, hot_tier2 = require_dump_containing(tier2_files, "function=demo:macro_call_storage_hot")
    if "tier2Installed=true" not in hot_tier2:
        raise AssertionError(f"Expected tier2 installation for hot macro caller: {hot_tier2_path} -> {hot_tier2!r}")
    if "executions=<retired>" not in hot_tier2:
        raise AssertionError(f"Expected retired tier1 execution profile for hot macro caller: {hot_tier2_path} -> {hot_tier2!r}")
    growth_tier2_path, growth_tier2 = require_dump_containing(tier2_files, "function=demo:macro_call_storage_tier2_growth")
    if "tier2Installed=true" not in growth_tier2:
        raise AssertionError(f"Expected tier2 installation for growth macro caller: {growth_tier2_path} -> {growth_tier2!r}")
    if "executions=<retired>" not in growth_tier2:
        raise AssertionError(f"Expected retired tier1 execution profile for growth macro caller: {growth_tier2_path} -> {growth_tier2!r}")
    large_tier2_path, large_tier2 = require_dump_containing(tier2_files, "function=demo:macro_call_storage_large_hot")
    if "tier2Installed=true" not in large_tier2:
        raise AssertionError(f"Expected tier2 installation for large hot macro caller: {large_tier2_path} -> {large_tier2!r}")
    if "executions=<retired>" not in large_tier2:
        raise AssertionError(f"Expected retired tier1 execution profile for large hot macro caller: {large_tier2_path} -> {large_tier2!r}")


def stop_server(client: RconClient, process: subprocess.Popen[str]) -> None:
    try:
        print(client.command("stop"))
    finally:
        client.close()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)


def main() -> int:
    prepare_datapack_copy()
    process = start_server()
    client = RconClient(RCON_HOST, RCON_PORT, RCON_PASSWORD)
    try:
        wait_for_rcon()
        client.connect()
        run_assertions(client)
        stop_server(client, process)
        print("Integration test passed")
        return 0
    except Exception:
        client.close()
        process.kill()
        process.wait(timeout=10)
        raise


if __name__ == "__main__":
    sys.exit(main())
