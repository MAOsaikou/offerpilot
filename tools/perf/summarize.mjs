import { readFile } from "node:fs/promises";
import { routes } from "./fixtures.mjs";
const before = JSON.parse(
  await readFile(`${process.argv[2]}/runs.json`, "utf8"),
);
const after = JSON.parse(
  await readFile(`${process.argv[3]}/runs.json`, "utf8"),
);
const median = (values) =>
  [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)];
const columns = ["perf", "a11y", "bp", "lcp", "tbt", "cls", "jsBytes"];
console.log(
  "| 页面 | 预设 | Perf | A11y | BP | LCP s | TBT ms | CLS | JS KiB |",
);
console.log("| --- | --- | --- | --- | --- | --- | --- | --- | --- |");
for (const route of routes)
  for (const preset of ["mobile", "desktop"]) {
    const phases = [before, after].map((runs) => {
      const rows = runs.filter(
        (r) => r.route === route.name && r.preset === preset,
      );
      if (rows.length !== 3)
        throw new Error(
          `${route.name}/${preset}: expected 3 runs, got ${rows.length}`,
        );
      return Object.fromEntries(
        columns.map((c) => [c, median(rows.map((r) => r[c]))]),
      );
    });
    const cells = columns.map((c) =>
      phases
        .map((p) => {
          if (c === "lcp") return (p[c] / 1000).toFixed(2);
          if (c === "tbt") return p[c].toFixed(0);
          if (c === "cls") return p[c].toFixed(4);
          if (c === "jsBytes") return (p[c] / 1024).toFixed(1);
          return p[c];
        })
        .join(" → "),
    );
    console.log(`| ${route.name} | ${preset} | ${cells.join(" | ")} |`);
  }
