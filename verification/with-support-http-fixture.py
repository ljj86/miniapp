#!/usr/bin/env python3
"""Run a command beside the real loopback Java API in one execution/network namespace.

Example: python3 verification/with-support-http-fixture.py --cwd apps/dining-mall -- node tests/service-http-live.test.mjs
The child receives SHOP_API_BASE=http://127.0.0.1:<port>/api/v1 and uses synthetic OTP identities.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--port', type=int, default=18081)
parser.add_argument('--cwd', type=Path)
parser.add_argument('--timeout', type=int, default=300)
parser.add_argument('command', nargs=argparse.REMAINDER)
args = parser.parse_args()
command = args.command[1:] if args.command[:1] == ['--'] else args.command
if not command:
    parser.error('supply the integration command after --')
if not 1024 <= args.port <= 65535:
    parser.error('port must be 1024..65535')
runner = Path(__file__).resolve().with_name('run-support-http-fixture.py')
with tempfile.TemporaryDirectory(prefix='shop-http-integration-') as temp:
    process = subprocess.Popen([sys.executable, str(runner), '--port', str(args.port), '--state', str(Path(temp) / 'state.json')])
    try:
        base = f'http://127.0.0.1:{args.port}/api/v1'
        deadline = time.monotonic() + 30
        while True:
            if process.poll() is not None:
                raise RuntimeError(f'Java fixture exited before readiness ({process.returncode})')
            try:
                with urllib.request.urlopen(base + '/health', timeout=1) as response:
                    if response.status == 200:
                        break
            except OSError:
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError('Java fixture readiness timed out')
            time.sleep(0.1)
        env = dict(os.environ, SHOP_API_BASE=base)
        try:
            result = subprocess.run(command, cwd=args.cwd, env=env, timeout=args.timeout, check=False)
            status = result.returncode
        except subprocess.TimeoutExpired:
            print('Integration command timed out', file=sys.stderr)
            status = 124
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    sys.exit(status)
