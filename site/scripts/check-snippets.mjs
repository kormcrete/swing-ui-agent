// Checks that code copied from the Order Desk demo into the docs still matches its source.
//
// A copied block is preceded by a marker naming its source lines in the demo repo:
//   <!-- snippet: src/test/java/.../PlaceOrderUiIT.java#L20-L45 -->     (.md)
//   {/* snippet: src/test/java/.../PlaceOrderUiIT.java#L20-L45 */}       (.mdx)
// The next fenced block must equal those lines after removing common indentation. A line that is only `// …` or
// `# …` stands for source lines left out.
//
// Usage: npm run check-snippets [-- --demo <dir>]   (or ORDER_DESK_DIR; default ../../swing-order-desk-demo)
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const site = resolve(fileURLToPath(import.meta.url), '../..');
const argAt = process.argv.indexOf('--demo');
const demo = resolve(site, argAt > 0 ? process.argv[argAt + 1] : process.env.ORDER_DESK_DIR ?? '../../swing-order-desk-demo');
if (!existsSync(demo)) {
	console.error(`check-snippets: no Order Desk checkout at ${demo} (pass --demo <dir> or set ORDER_DESK_DIR)`);
	process.exit(2);
}

const MARKER = /(?:<!--|\{\/\*)\s*snippet:\s*(\S+?)#L(\d+)-L(\d+)\s*(?:-->|\*\/\})/;
const GAP = /^\s*(\/\/|#)\s*…\s*$/;

function pages(dir) {
	return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
		e.isDirectory() ? pages(join(dir, e.name)) : /\.mdx?$/.test(e.name) ? [join(dir, e.name)] : []);
}

function dedent(lines) {
	const indents = lines.filter((l) => l.trim()).map((l) => l.match(/^ */)[0].length);
	const cut = indents.length ? Math.min(...indents) : 0;
	return lines.map((l) => l.slice(cut).trimEnd());
}

/** Do the block's lines appear in order in the source, with gaps only where the block has a gap line? */
function matches(block, source) {
	const parts = [[]];
	for (const line of block) GAP.test(line) ? parts.push([]) : parts.at(-1).push(line);
	let at = 0;
	for (let p = 0; p < parts.length; p++) {
		const part = parts[p];
		if (!part.length) continue;
		const strip = (ls) => dedent(ls).join('\n');
		let found = -1;
		for (let i = at; i + part.length <= source.length; i++) {
			// each part is compared on its own indentation, since a gap may cut nesting levels
			if (strip(source.slice(i, i + part.length)) === strip(part)) { found = i; break; }
			if (p === 0) break; // the first part starts at the marker's first line
		}
		if (found < 0) return false;
		at = found + part.length;
	}
	return parts.at(-1).length === 0 || at === source.length;
}

let checked = 0;
const failures = [];
for (const file of pages(join(site, 'src/content/docs'))) {
	const lines = readFileSync(file, 'utf8').split('\n');
	lines.forEach((line, i) => {
		const m = line.match(MARKER);
		if (!m) return;
		const where = `${relative(site, file)}:${i + 1}`;
		const [, path, from, to] = m;
		const open = lines.findIndex((l, j) => j > i && /^\s*```/.test(l));
		const close = open < 0 ? -1 : lines.findIndex((l, j) => j > open && /^\s*```\s*$/.test(l));
		if (open < 0 || close < 0) return failures.push(`${where}: no code block after the marker`);
		const src = join(demo, path);
		if (!existsSync(src)) return failures.push(`${where}: ${path} does not exist in ${demo}`);
		const source = dedent(readFileSync(src, 'utf8').split('\n').slice(from - 1, Number(to)));
		const block = dedent(lines.slice(open + 1, close));
		checked++;
		if (!matches(block, source)) failures.push(`${where}: the block differs from ${path}#L${from}-L${to}`);
	});
}

for (const f of failures) console.error(`check-snippets: ${f}`);
console.log(`check-snippets: ${checked} snippet(s) against ${demo}, ${failures.length} failing`);
process.exit(failures.length ? 1 : 0);
