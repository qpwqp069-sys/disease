#!/usr/bin/env node
/**
 * Генерирует wiki/data/mod.json из исходников мода, чтобы вики никогда не расходилась с кодом.
 *
 *  - gradle.properties              -> версия мода, Minecraft, Fabric Loader, Fabric API
 *  - Disease.java (enum)            -> id, название, краткое описание
 *  - DiseaseManager.details()       -> подробная механика ("Как работает")
 *  - DiseaseManager.isCompatible()  -> пары несовместимых болезней
 *  - DiseaseManager.incompatibleReason() -> пояснение к каждой паре
 *
 * Категории и тяжесть — данные только для вики, они задаются в META ниже.
 * Запуск: node scripts/build-wiki-data.mjs [путь_вывода]
 */
import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = resolve(ROOT, process.argv[2] ?? "wiki/data/mod.json");
const REPO = "qpwqp069-sys/disease";

const read = (p) => readFileSync(resolve(ROOT, p), "utf8");
const fail = (msg) => {
  console.error(`[wiki-data] ОШИБКА: ${msg}`);
  process.exit(1);
};

// ---------- gradle.properties ----------
const props = Object.fromEntries(
  read("gradle.properties")
    .split(/\r?\n/)
    .filter((l) => l.trim() && !l.trim().startsWith("#") && l.includes("="))
    .map((l) => [l.slice(0, l.indexOf("=")).trim(), l.slice(l.indexOf("=") + 1).trim()]),
);
for (const k of ["version", "minecraft_version", "loader_version", "fabric_api_version", "archives_base_name"]) {
  if (!props[k]) fail(`в gradle.properties нет ключа ${k}`);
}

// ---------- Disease.java ----------
const diseaseSrc = read("src/main/java/com/diseasemod/Disease.java");
const enumRe = /^\s*([A-Z_]+)\(\s*"([a-z_]+)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)\s*[,;]/gm;
const diseases = [];
for (const m of diseaseSrc.matchAll(enumRe)) {
  diseases.push({ key: m[1], id: m[2], title: unescape(m[3]), short: unescape(m[4]) });
}
if (diseases.length === 0) fail("не удалось разобрать enum Disease");

// ---------- DiseaseManager.java ----------
const managerSrc = read("src/main/java/com/diseasemod/DiseaseManager.java");

const details = {};
for (const m of managerSrc.matchAll(/case\s+([A-Z_]+)\s*->\s*"((?:[^"\\]|\\.)*)"\s*;/g)) {
  details[m[1]] = unescape(m[2]);
}

const compatBody = methodBody(managerSrc, "isCompatible");
const pairs = new Map();
for (const line of compatBody.split("\n")) {
  if (!/return\s+false/.test(line)) continue;
  const cands = [...line.matchAll(/candidate\s*==\s*Disease\.([A-Z_]+)/g)].map((m) => m[1]);
  const owned = [...line.matchAll(/st\.has\(Disease\.([A-Z_]+)\)/g)].map((m) => m[1]);
  for (const a of cands) for (const b of owned) pairs.set(pairKey(a, b), { a, b, reason: "" });
}

const reasonBody = methodBody(managerSrc, "incompatibleReason");
for (const m of reasonBody.matchAll(/if\s*\(([\s\S]*?)\)\s*\{\s*return\s+"((?:[^"\\]|\\.)*)"\s*;/g)) {
  const names = [...new Set([...m[1].matchAll(/Disease\.([A-Z_]+)/g)].map((x) => x[1]))];
  if (names.length >= 2) {
    const p = pairs.get(pairKey(names[0], names[1]));
    if (p) p.reason = unescape(m[2]);
  }
}

// ---------- Метаданные только для вики ----------
// cat: vision | speech | mind | phobia | body | inner | meme
// sev: 1 лёгкая, 2 средняя, 3 тяжёлая, 4 смертельная
const META = {
  BLINDNESS: ["vision", 3], NIGHT_BLINDNESS: ["vision", 2], TUNNEL_VISION: ["vision", 2],
  MUTENESS: ["speech", 1], DEAFNESS: ["speech", 2], STUTTER: ["speech", 1], ALEXIA: ["speech", 3], DYSLEXIA: ["speech", 2],
  OCD: ["mind", 4], SCHIZOPHRENIA: ["mind", 1], PARANOIA: ["mind", 1], EPILEPSY: ["mind", 3], NARCOLEPSY: ["mind", 2],
  INSOMNIA: ["mind", 1], VERTIGO: ["mind", 1], TREMOR: ["mind", 1], CEREBRAL_PALSY: ["mind", 2],
  ACROPHOBIA: ["phobia", 3], AQUAPHOBIA: ["phobia", 3], CLAUSTROPHOBIA: ["phobia", 3], HELIOPHOBIA: ["phobia", 3], ALLERGY: ["phobia", 2],
  NO_LEFT_HAND: ["body", 2], LIMP: ["body", 2], WEAK_HANDS: ["body", 2], SHORT_ARMS: ["body", 2], MUSCLE_WEAKNESS: ["body", 2],
  DWARFISM: ["body", 1], GIGANTISM: ["body", 2], OSTEOPOROSIS: ["body", 2],
  ASTHMA: ["inner", 2], DIABETES: ["inner", 3], FEVER: ["inner", 1], HEMOPHILIA: ["inner", 2], ANEMIA: ["inner", 2],
  HEART_DISEASE: ["inner", 2], HICCUPS: ["inner", 1],
  BEE_DISRESPECT: ["meme", 1],
};
// Болезни, которые наносят урон или убивают
const DAMAGING = new Set(["OCD", "ACROPHOBIA", "AQUAPHOBIA", "CLAUSTROPHOBIA", "HELIOPHOBIA", "ALLERGY", "HEMOPHILIA", "OSTEOPOROSIS", "BEE_DISRESPECT"]);

const warnings = [];
const out = diseases.map((d, i) => {
  const meta = META[d.key];
  if (!meta) warnings.push(`нет категории для ${d.key} (добавьте в META)`);
  if (!details[d.key]) warnings.push(`нет подробностей для ${d.key} в details()`);
  const incompatible = [...pairs.values()]
    .filter((p) => p.a === d.key || p.b === d.key)
    .map((p) => (p.a === d.key ? p.b : p.a));
  return {
    n: i + 1,
    id: d.id,
    title: d.title,
    short: d.short,
    details: details[d.key] ?? "",
    category: meta?.[0] ?? "other",
    severity: meta?.[1] ?? 2,
    damaging: DAMAGING.has(d.key),
    incompatible: [...new Set(incompatible)].map((k) => diseases.find((x) => x.key === k)?.id).filter(Boolean),
  };
});
for (const k of Object.keys(META)) {
  if (!diseases.some((d) => d.key === k)) warnings.push(`META содержит ${k}, которого нет в enum Disease`);
}

const idOf = (k) => diseases.find((x) => x.key === k)?.id;
const jar = `${props.archives_base_name}-${props.version}.jar`;
const data = {
  generatedAt: new Date().toISOString(),
  version: props.version,
  minecraft: props.minecraft_version,
  loader: props.loader_version,
  fabricApi: props.fabric_api_version,
  repo: `https://github.com/${REPO}`,
  releaseUrl: `https://github.com/${REPO}/releases/tag/v${props.version}`,
  downloadUrl: `https://github.com/${REPO}/releases/download/v${props.version}/${jar}`,
  jar,
  diseases: out,
  pairs: [...pairs.values()]
    .map((p) => ({ a: idOf(p.a), b: idOf(p.b), reason: p.reason }))
    .filter((p) => p.a && p.b),
};

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(data, null, 2));
// Иконка мода для favicon и шапки (берётся из ресурсов мода, в git не дублируется)
const iconOut = resolve(dirname(OUT), "..", "assets", "icon.png");
mkdirSync(dirname(iconOut), { recursive: true });
copyFileSync(resolve(ROOT, "src/main/resources/assets/diseases/icon.png"), iconOut);
for (const w of warnings) console.warn(`[wiki-data] предупреждение: ${w}`);
console.log(`[wiki-data] v${data.version}: ${out.length} болезней, ${data.pairs.length} пар несовместимости -> ${OUT}`);

// ---------- helpers ----------
function pairKey(a, b) {
  return [a, b].sort().join("|");
}
function unescape(s) {
  return s.replace(/\\(["\\nt])/g, (_, c) => ({ n: "\n", t: "\t" })[c] ?? c);
}
/** Тело Java-метода по имени (по балансу фигурных скобок). */
function methodBody(src, name) {
  const start = src.search(new RegExp(`\\b${name}\\s*\\([^)]*\\)\\s*\\{`));
  if (start < 0) fail(`метод ${name} не найден в DiseaseManager.java`);
  let i = src.indexOf("{", start);
  let depth = 0;
  const from = i;
  for (; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}" && --depth === 0) return src.slice(from + 1, i);
  }
  fail(`не удалось найти конец метода ${name}`);
}
