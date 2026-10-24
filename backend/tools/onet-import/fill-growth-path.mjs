// O*NET 31.0 Job Zone preparation summaries, translated/adapted into Chinese.
// CC BY 4.0; National Center for O*NET Development / USDOL/ETA.
import { createHash } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const defaultSeed = fileURLToPath(new URL('../../offerpilot-bootstrap/src/main/resources/seed/occupations-onet.json', import.meta.url));
// Pin both tables: a new release requires reviewing the mapping AND translations.
const hashes = {
  'job_zones.csv': '093f01891ac2e602a8cc6c859ecb619b5bc00eb50cd3f3c1134c05b704481944',
  'job_zone_reference.csv': '75e21c0bbe0ec4d70428863b6930fbff0e279537faba1e630d7f2105ba2b6038',
};
export const paths = {
  4: '职业准备（Job Zone 4）：多数职业通常需四年制本科学历（部分例外），并积累较多相关技能、知识或经验；通常需数年相关工作经验、在岗培训和/或职业培训。',
  5: '职业准备（Job Zone 5）：多数职业通常需研究生教育（如硕士，部分需博士或专业学位）；需广泛的技能、知识与经验，许多职业需五年以上经验；可能需在岗培训，但通常入职前已具备所需能力或培训。',
};

// RFC 4180 fields, including escaped quotes and newlines inside quoted fields.
function csvRows(text) {
  const rows = [];
  let row = [], field = '', quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"') {
      if (quoted && text[i + 1] === '"') { field += '"'; i++; }
      else quoted = !quoted;
    } else if (!quoted && (c === ',' || c === '\n')) {
      row.push(field); field = '';
      if (c === '\n') { rows.push(row); row = []; }
    } else if (c !== '\r' || quoted) field += c;
  }
  if (quoted) throw new Error('Unclosed CSV quote');
  if (row.length || field) rows.push([...row, field]);
  const [headers, ...data] = rows;
  return data.map(values => Object.fromEntries(headers.map((h, i) => [h, values[i]])));
}

export async function loadZones(csvDir) {
  const tables = {};
  for (const [file, hash] of Object.entries(hashes)) {
    const bytes = await readFile(resolve(csvDir, file));
    if (createHash('sha256').update(bytes).digest('hex') !== hash) {
      throw new Error(`${file}: expected official O*NET 31.0 bytes; review version/translation before updating`);
    }
    tables[file] = csvRows(bytes.toString('utf8'));
  }
  // The pinned reference supplies Education, Experience, and Job Training prose.
  for (const zone of [4, 5]) {
    const ref = tables['job_zone_reference.csv'].find(row => Number(row['Job Zone']) === zone);
    if (!ref?.Education || !ref.Experience || !ref['Job Training']) throw new Error(`Missing Job Zone ${zone} preparation`);
  }
  return new Map(tables['job_zones.csv'].map(row => [row['O*NET-SOC Code'], Number(row['Job Zone'])]));
}

export function fillGrowthPaths(seed, zones) {
  const result = structuredClone(seed);
  for (const entry of result.occupations) {
    const blank = entry.growthPath == null || entry.growthPath.trim() === '';
    // Preserve all existing editorial paths and their source, including curated mappings.
    if (!blank && entry.growthPathSource !== 'onet-job-zone') continue;
    const zone = zones.get(entry.onetCode);
    if (!paths[zone]) throw new Error(`${entry.id}: missing or unsupported Job Zone for ${entry.onetCode}`);
    if (!blank && entry.growthPath !== paths[zone]) throw new Error(`${entry.id}: generated path differs from Job Zone ${zone}`);
    entry.growthPath = paths[zone];
    entry.growthPathSource = 'onet-job-zone';
  }
  return result;
}

export async function updateSeed(csvDir, seedFile = defaultSeed, check = false) {
  const zones = await loadZones(csvDir);
  const original = await readFile(seedFile, 'utf8');
  const seed = JSON.parse(original);
  const next = fillGrowthPaths(seed, zones);
  // The seed convention is one occupation per line. Preserve other bytes/metadata.
  const changed = new Map(next.occupations.flatMap((entry, i) =>
    JSON.stringify(entry) === JSON.stringify(seed.occupations[i]) ? [] : [[entry.id, entry]]));
  const output = original.replace(/^(\s*)(\{"id":.*\})(,?)$/gm, (line, indent, json, comma) => {
    const entry = changed.get(JSON.parse(json).id);
    if (!entry) return line;
    changed.delete(entry.id);
    return indent + JSON.stringify(entry) + comma;
  });
  if (changed.size) throw new Error('Expected seed format: one occupation per line, id first');
  // Verify that formatting preserved every field, including salaries.
  if (JSON.stringify(JSON.parse(output)) !== JSON.stringify(next)) throw new Error('Seed rewrite mismatch');
  if (check && output !== original) throw new Error('Seed growth paths need regeneration');
  if (!check && output !== original) await writeFile(seedFile, output);
  return next.occupations.filter(entry => entry.growthPathSource === 'onet-job-zone').length;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { values, positionals } = parseArgs({ options: { check: { type: 'boolean' } }, allowPositionals: true });
  if (positionals.length < 1 || positionals.length > 2) throw new Error('Usage: node fill-growth-path.mjs <CSV directory> [seed file] [--check]');
  const count = await updateSeed(positionals[0], positionals[1], values.check);
  console.log(`${values.check ? 'Checked' : 'Updated'}: ${count} onet-job-zone paths; other fields preserved`);
}
