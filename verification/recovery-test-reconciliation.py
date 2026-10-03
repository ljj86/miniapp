#!/usr/bin/env python3
"""Run the saved reconciliation scenarios with this restored workspace layout."""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parent.parent
repo=ROOT/'runtime/tools/maven-repository/com/fasterxml/jackson/core'
cp=':'.join(str(repo/name/'2.13.5'/f'{name}-2.13.5.jar') for name in ['jackson-databind','jackson-core','jackson-annotations'])
out=ROOT/'verification/target-reconciliation';out.mkdir(exist_ok=True)
source=ROOT/'runtime/backend/yudao-server/src/main/java/cn/iocoder/yudao/server/simulation'
files=[source/(n+'.java') for n in ['SimState','SimContext','SimException','SimModule','CommerceModule','FinanceModule','ReconciliationModule']]
files.append(ROOT/'verification/tests/ReconciliationModuleScenarios.java')
subprocess.run(['java','-Xmx512m','--module','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-cp',cp,'-d',str(out)]+list(map(str,files)),check=True)
subprocess.run(['java','-Xmx512m','-XX:ActiveProcessorCount=2','-cp',str(out)+':'+cp,'cn.iocoder.yudao.server.simulation.ReconciliationModuleScenarios'],check=True)
