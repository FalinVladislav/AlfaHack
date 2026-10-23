"""Opt-in live checks against the configured provider, using synthetic H2 data only.

Run: python scripts/check_scenario_llm.py --maven <path-to-mvn.cmd>
Provider credentials are loaded into the child inference process and never printed.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--maven", default=shutil.which("mvn") or shutil.which("mvn.cmd"))
    args = parser.parse_args()
    if not args.maven:
        parser.error("Specify --maven with the Maven executable path")
    root = Path(__file__).resolve().parents[1]
    env = os.environ.copy()
    for file in (root / ".env", root / "inference/.env"):
        if file.exists():
            for line in file.read_text(encoding="utf-8-sig").splitlines():
                if "=" not in line or line.lstrip().startswith("#"):
                    continue
                name, value = line.split("=", 1)
                name = name.strip()
                if name in {"API_TOOKEN_CLUB", "OPENAI_BASE_URL", "OPENAI_MODEL", "OPENAI_TIMEOUT_SECONDS", "OPENAI_MAX_COMPLETION_TOKENS", "OPENAI_TOOLS_MAX_COMPLETION_TOKENS"}:
                    env.setdefault(name, value.strip().strip("\"'"))
    if not env.get("API_TOOKEN_CLUB", "").strip():
        parser.error("Provider credential is not configured")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    env.update(INFERENCE_HOST="127.0.0.1", INFERENCE_PORT=str(port), OPENAI_TOOLS_MAX_COMPLETION_TOKENS=env.get("OPENAI_TOOLS_MAX_COMPLETION_TOKENS", "16384"))
    output = root / "target"
    output.mkdir(exist_ok=True)
    with (output / "scenario-live-inference.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen([sys.executable, str(root / "inference/server.py")], cwd=root, env=env, stdout=log, stderr=log)
        try:
            url = f"http://127.0.0.1:{port}"
            for _ in range(100):
                if process.poll() is not None:
                    raise RuntimeError("Local inference process exited; see target/scenario-live-inference.log")
                try:
                    with urlopen(url + "/health", timeout=1) as response:
                        if json.load(response).get("configured"):
                            break
                except (URLError, OSError):
                    time.sleep(.1)
            else:
                raise RuntimeError("Local inference did not become ready")
            # Only the inference process receives the provider credential.
            test_env = os.environ.copy()
            test_env.pop("API_TOOKEN_CLUB", None)
            test_env["SCENARIO_LIVE_URL"] = url
            command = [args.maven, "-o", "-f", str(root / "backend/pom.xml"),
                       "-Dmaven.repo.local=" + str(root / ".maven-repository"),
                       "-Dtest=ScenarioDialogsIntegrationTest#liveSparseGoalsProduceUsefulQuestionsWithoutInventingAPlan", "test"]
            with (output / "scenario-live-tests.log").open("w", encoding="utf-8") as test_log:
                completed = subprocess.run(command, cwd=root, env=test_env, stdout=test_log, stderr=test_log)
            print("Live scenario checks: " + ("PASSED" if completed.returncode == 0 else "FAILED"))
            print("Report: target/scenario-live-tests.log; dialog traces: backend/target/scenario-live-*.json")
            return completed.returncode
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
