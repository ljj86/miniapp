# Oct 3 recovery verification

The upstream source baseline and saved checkpoint are preserved under recovery-oct3/baseline and recovery-oct3/checkpoint. The saved checkpoint is dated 2026-10-02T14:33:11Z and marked IN_PROGRESS. It is not a recovered copy of any later unsaved final state.

The current miniapp-baseline/runtime contains the baseline backend/admin/miniapp trees plus the saved overlay. Three explicit recovery repairs are recorded: reauthenticate the synthetic user after the test clock advances 900 seconds; restore the candidate seed amounts of 10000/2000 minor units without formal approval; enforce immutable existing accounting/review evidence in the JDBC aggregate transaction. Detailed original/repaired hashes and minimal patches are in the evidence reports.

## Focused rerun

From miniapp-baseline:

- verification/recovery-mvn.sh -f verification/pom-with-guard.xml test
- python3 verification/recovery-test-reconciliation.py
- cd runtime/foundation-ui, then NODE_OPTIONS=--max-old-space-size=384 node --test tests/*.test.js
- From runtime/foundation-ui: NODE_OPTIONS=--max-old-space-size=384 node node_modules/vite/bin/vite.js build

Maven 3.9.11 is installed under runtime/tools/apache-maven-3.9.11. The wrapper uses system Java, a generated temporary proxy settings file and workspace-local runtime/tools/maven-repository. It pins all Maven downloads to official Maven Central. On a fresh recovery host, retrieve the official archive using the saved checkpoint download metadata and verify its SHA-256 before extraction.

Vue dependencies were installed from https://registry.npmjs.org using the saved frozen pnpm lockfile. For a fresh dependency install, set PNPM_HOME, XDG_DATA_HOME and XDG_CACHE_HOME to writable runtime/tools subdirectories, set NODE_OPTIONS=--max-old-space-size=384, and run pnpm install --frozen-lockfile --store-dir ../tools/pnpm-store --registry=https://registry.npmjs.org from runtime/foundation-ui.

The original verification/pom.xml and test-reconciliation.py are preserved. The separate guard POM includes the recovered guard source/test and adds spring-test 5.3.31. The reconciliation wrapper adapts only paths/compiler invocation: the available Java installation includes jdk.compiler but no javac launcher or release-8 API symbols, so source 8/target 8 compilation is used as in Maven. This does not certify Java 8 runtime/API compatibility.

No backend, MySQL or Redis service was started. No database, HTTP/browser, full upstream backend reactor, PC/admin or uniapp bundle acceptance is asserted. NotificationDelivery and SimulationExtensions sources were not recovered. The original cloud runtime scripts require previously prepared tool/service paths and were not executed.
