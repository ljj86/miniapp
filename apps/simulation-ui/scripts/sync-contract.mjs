import { readFile, writeFile } from "node:fs/promises";
const roots = [new URL("../../../contract/", import.meta.url), new URL("../../backend/yudao-server/src/main/resources/simulation/", import.meta.url)];
let source;
for (const root of roots) {
  try { source = { api: JSON.parse(await readFile(new URL("openapi.json", root), "utf8")), operations: JSON.parse(await readFile(new URL("api_operations.json", root), "utf8")) }; break; }
  catch (error) { if (error.code !== "ENOENT") throw error; }
}
if (!source) throw Error("Frozen contract not found. Run this inside the complete repository or prepared runtime layout.");
const { api, operations } = source;
const result = JSON.stringify({ operations, schemas: api.components.schemas, paths: api.paths }, null, 2) + "\n";
const target = new URL("../src/contract.json", import.meta.url);
if (process.argv.includes("--check")) {
  if (await readFile(target, "utf8") !== result) throw Error("Frontend contract snapshot is stale. Run pnpm contract:sync.");
  console.log(`Contract snapshot matches ${operations.length} operations`);
} else { await writeFile(target, result); console.log(`Synced ${operations.length} operations`); }
