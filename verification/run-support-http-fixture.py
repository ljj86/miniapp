#!/usr/bin/env python3
"""Run the loopback-only real servlet integration fixture after the focused Maven tests.

Uses a TEST atomic JSON store, not MySQL, not the production Spring application.
No network binding outside 127.0.0.1. All seed data is synthetic.
"""
import argparse
import os
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--port', type=int, default=18081)
parser.add_argument('--state', type=Path, default=Path(__file__).resolve().parent / 'support-http-data' / 'state.json')
args = parser.parse_args()
if not 1024 <= args.port <= 65535:
    parser.error('port must be 1024..65535')
root = Path(__file__).resolve().parent
reports = list((root / 'target-with-guard' / 'surefire-reports').glob('TEST-*.xml'))
classpath = None
for report in reports:
    for prop in ET.parse(report).getroot().findall('properties/property'):
        if prop.get('name') == 'java.class.path':
            classpath = prop.get('value')
            break
    if classpath:
        break
if not classpath or not (root / 'target-with-guard' / 'test-classes' / 'cn/iocoder/yudao/server/simulation/SupportHttpFixture.class').is_file():
    parser.error('Run mvn -f verification/pom-with-guard.xml test first')
os.execvp('java', ['java', '-Xmx384m', '-cp', classpath, 'cn.iocoder.yudao.server.simulation.SupportHttpFixture', str(args.port), str(args.state.resolve())])
