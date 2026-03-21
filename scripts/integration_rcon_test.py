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
        LOG_PATH.unlink()
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


def run_assertions(client: RconClient) -> None:
    checks = [
        ("reload", "Reloading!"),
        ("scoreboard objectives add mercury_exec dummy", None),
        ("scoreboard objectives add mercury_inline dummy", None),
        ("function demo:execute_if_score_set", "Running function demo:execute_if_score_set"),
        ("scoreboard players get out mercury_exec", "out has 11 [mercury_exec]"),
        ("function demo:execute_store_score_get", "Running function demo:execute_store_score_get"),
        ("scoreboard players get stored mercury_exec", "stored has 23 [mercury_exec]"),
        ("function demo:execute_store_storage_get", "Running function demo:execute_store_storage_get"),
        ("data get storage demo:test payload", "Storage demo:test has the following contents: {value: 17}"),
        ("function demo:inline_entry", "Running function demo:inline_entry"),
        ("scoreboard players get alpha mercury_inline", "alpha has 9 [mercury_inline]"),
        ("mercury dump classes", "Exported "),
    ]

    for command, expected in checks:
        response = client.command(command)
        print(f"$ {command}")
        print(response)
        if expected is not None and expected not in response:
            raise AssertionError(f"Unexpected response for {command!r}: {response!r}")

    class_dir = RUN_DIR / "mercury" / "dumped" / "classes" / "demo"
    if not (class_dir / "execute_if_score_set.class").exists():
        raise AssertionError("Missing execute_if_score_set.class export")
    if not (class_dir / "execute_store_score_get.class").exists():
        raise AssertionError("Missing execute_store_score_get.class export")
    if not (class_dir / "execute_store_storage_get.class").exists():
        raise AssertionError("Missing execute_store_storage_get.class export")
    if not (class_dir / "inline_entry.class").exists():
        raise AssertionError("Missing inline_entry.class export")
    if (class_dir / "inline_mid.class").exists():
        raise AssertionError("inline_mid.class should have been inlined away")
    if (class_dir / "inline_leaf.class").exists():
        raise AssertionError("inline_leaf.class should have been inlined away")
    if (class_dir / "scoreboard_add.class").exists():
        raise AssertionError("scoreboard_add.class should have been inlined away")


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
