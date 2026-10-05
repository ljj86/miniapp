import {build} from 'vite'
import path from 'node:path'
import fs from 'node:fs'
await build({configFile:path.resolve('vite.config.js'),build:{outDir:path.resolve('.test-build'),emptyOutDir:true,lib:{entry:path.resolve('tests/ui-harness-entry.js'),formats:['es'],fileName:'harness'},rollupOptions:{output:{entryFileNames:'harness.mjs',chunkFileNames:'[name]-[hash].mjs'}},minify:false}})
fs.writeFileSync(path.resolve('.test-build/package.json'),'{"type":"module"}')
