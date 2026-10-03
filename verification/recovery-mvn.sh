#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export MAVEN_OPTS='-Xms64m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2'
settings="$(mktemp "$PROJECT_ROOT/runtime/tools/maven-settings.XXXXXX.xml")"
trap 'rm -f "$settings"' EXIT
python3 - "$settings" "$PROJECT_ROOT" <<'PYSETTINGS'
import os,sys,urllib.parse,xml.etree.ElementTree as E
s=E.Element('settings',xmlns='http://maven.apache.org/SETTINGS/1.2.0')
E.SubElement(s,'localRepository').text=sys.argv[2]+'/runtime/tools/maven-repository'
mirrors=E.SubElement(s,'mirrors'); mirror=E.SubElement(mirrors,'mirror')
for key,value in {'id':'verified-maven-central','name':'Official Maven Central only','url':'https://repo.maven.apache.org/maven2','mirrorOf':'*'}.items(): E.SubElement(mirror,key).text=value
u=urllib.parse.urlparse(os.environ.get('HTTPS_PROXY',''))
if u.hostname:
 if u.username: raise SystemExit('Refusing to write proxy credentials')
 proxies=E.SubElement(s,'proxies')
 for protocol in ['http','https']:
  proxy=E.SubElement(proxies,'proxy')
  for key,value in {'id':'recovery-'+protocol,'active':'true','protocol':protocol,'host':u.hostname,'port':str(u.port or 80),'nonProxyHosts':'localhost|127.0.0.1|[::1]'}.items(): E.SubElement(proxy,key).text=value
E.indent(s); E.ElementTree(s).write(sys.argv[1],encoding='unicode',xml_declaration=True)
PYSETTINGS
"$PROJECT_ROOT/runtime/tools/apache-maven-3.9.11/bin/mvn" --batch-mode --no-transfer-progress -s "$settings" "$@"
