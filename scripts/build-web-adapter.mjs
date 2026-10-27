import { mkdirSync, copyFileSync, writeFileSync, readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { build } from 'esbuild';
const root = fileURLToPath(new URL('../', import.meta.url));
const output = resolve(root, process.argv[2] || '.build/web-adapter');
mkdirSync(resolve(output, 'dist'), { recursive: true });
mkdirSync(resolve(output, 'windows'), { recursive: true });
const built = await build({
  entryPoints: [resolve(root, 'scripts/web-adapter-entry.mjs')],
  bundle: true,
  platform: 'node',
  format: 'esm',
  metafile: true,
  external: ['./runtime-session.js'],
  outfile: resolve(output, 'dist/desktop-ipc.mjs'),
});
writeFileSync(
  resolve(output, 'dist/runtime-session.js'),
  'export class RuntimeError extends Error { constructor(status, message) { super(message); this.status = status; } }\n',
  'utf8',
);
writeFileSync(
  resolve(output, 'package.json'),
  '{"name":"codex-mobile-desktop-bridge","private":true,"type":"module","license":"MIT"}\n',
  'utf8',
);
copyFileSync(resolve(root, 'LICENSE'), resolve(output, 'LICENSE'));
copyFileSync(
  resolve(root, 'packages/codex-adapter/windows/desktop-pipe.ps1'),
  resolve(output, 'windows/desktop-pipe.ps1'),
);
const inputs = [
  ...new Set([
    'scripts/web-adapter-entry.mjs',
    'packages/codex-adapter/src/desktop-ipc.ts',
    'packages/codex-adapter/src/desktop-framing.ts',
    'packages/codex-adapter/src/desktop-live-window.ts',
    'packages/codex-adapter/src/desktop-platform.ts',
    'packages/codex-adapter/src/windows-process.ts',
    'packages/codex-adapter/windows/desktop-pipe.ps1',
    ...Object.keys(built.metafile.inputs),
  ]),
];
const sources = {};
for (const file of inputs) {
  const target = resolve(output, 'source', file);
  mkdirSync(dirname(target), { recursive: true });
  copyFileSync(resolve(root, file), target);
  sources[file] = createHash('sha256').update(readFileSync(target)).digest('hex');
}
const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
writeFileSync(
  resolve(output, 'PROVENANCE.json'),
  JSON.stringify(
    {
      upstream: 'https://github.com/easyaigc/agent-hub',
      fork: 'https://github.com/wang1733238039/codex-mobile',
      revision,
      sources,
      outputSha256: createHash('sha256')
        .update(readFileSync(resolve(output, 'dist/desktop-ipc.mjs')))
        .digest('hex'),
      scope:
        'DesktopIpc only; no Host server, account, credentials, execution session, or arbitrary remote RPC',
    },
    null,
    2,
  ) + '\n',
  'utf8',
);
console.log(output);
