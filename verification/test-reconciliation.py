#!/usr/bin/env python3
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parent.parent
java=ROOT/'runtime/tools/jdk21-debian/usr/lib/jvm/java-21-openjdk-amd64/bin'
repo=ROOT/'runtime/tools/maven-repository/com/fasterxml/jackson/core'
cp=':'.join(str(repo/name/'2.13.5'/f'{name}-2.13.5.jar') for name in ['jackson-databind','jackson-core','jackson-annotations'])
out=ROOT/'simulation-dev/test-classes-reconciliation';out.mkdir(exist_ok=True)
source=ROOT/'runtime/backend/yudao-server/src/main/java/cn/iocoder/yudao/server/simulation'
files=[source/(n+'.java') for n in ['SimState','SimContext','SimException','SimModule','CommerceModule','FinanceModule','ReconciliationModule']]
files.append(ROOT/'simulation-dev/tests/ReconciliationModuleScenarios.java')
subprocess.run([str(java/'javac'),'--release','8','-cp',cp,'-d',str(out)]+list(map(str,files)),check=True)
subprocess.run([str(java/'java'),'-cp',str(out)+':'+cp,'cn.iocoder.yudao.server.simulation.ReconciliationModuleScenarios'],check=True)
