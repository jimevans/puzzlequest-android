// Writes the app's logo, launcher icon layers and notification icon as Android vector drawables
// from the brand's logo, brand/logo.svg in the server repository, whose
// README describes the brand. Its three paths are the piece's outline
// (aubergine, the whole silhouette), its fill (pale gold), and the Q
// (aubergine, even-odd); Android's path data takes SVG's as it is.
//
//   node scripts/brand-drawables.mjs [path/to/brand]
//
// The brand folder defaults to ../therapypub-puzzlequest/brand.
import { readFileSync, writeFileSync } from "node:fs";
import path from "node:path";

const root = path.join(path.dirname(new URL(import.meta.url).pathname), "..");
const brand = process.argv[2] ?? path.join(root, "..", "therapypub-puzzlequest", "brand");
const svg = readFileSync(path.join(brand, "logo.svg"), "utf8");

const [minX, minY, width, height] = svg
  .match(/viewBox="([^"]+)"/)[1]
  .split(/\s+/)
  .map(Number);
const paths = [...svg.matchAll(/<path fill="(#[0-9a-f]{6})"( fill-rule="evenodd")? d="([^"]+)"/g)].map(
  ([, fill, evenOdd, data]) => ({ fill, evenOdd: Boolean(evenOdd), data })
);
if (paths.length !== 3) throw new Error(`expected 3 paths in logo.svg, found ${paths.length}`);
const [outline, fill, q] = paths;

const out = path.join(root, "app", "src", "main", "res", "drawable");
const header = `<?xml version="1.0" encoding="utf-8"?>
<!-- Made by scripts/brand-drawables.mjs from the brand's logo.svg; don't edit. -->`;
const pathElement = ({ fill, evenOdd, data }, color = fill) =>
  `        <path
            android:fillColor="${color}"${evenOdd ? `\n            android:fillType="evenOdd"` : ""}
            android:pathData="${data}" />`;

/** The paths, moved so the logo's corner is at the origin and scaled. */
const group = (elements, { scale = 1, x = 0, y = 0 } = {}) => `    <group
        android:scaleX="${round(scale)}"
        android:scaleY="${round(scale)}"
        android:translateX="${round(x - minX * scale)}"
        android:translateY="${round(y - minY * scale)}">
${elements.join("\n")}
    </group>`;
const round = (n) => Number(n.toFixed(4));

// The logo itself, at its own proportions; screens size it.
writeFileSync(
  path.join(out, "logo.xml"),
  `${header}
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${round(width / 8)}dp"
    android:height="${round(height / 8)}dp"
    android:viewportWidth="${width}"
    android:viewportHeight="${height}">
${group(paths.map((p) => pathElement(p)))}
</vector>
`
);

// The launcher icon's layers are 108 dp squares, of which a circle 66 dp
// across is always shown; the logo is 0.443 of the square (as
// brand/icons/android-foreground.png is), so it fits inside that circle.
const scale = (108 * 0.443) / Math.max(width, height);
const placed = { scale, x: (108 - width * scale) / 2, y: (108 - height * scale) / 2 };
const layer = (name, elements) =>
  writeFileSync(
    path.join(out, `${name}.xml`),
    `${header}
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${group(elements, placed)}
</vector>
`
  );

layer("ic_launcher_foreground", paths.map((p) => pathElement(p)));
// Themed icons: Android tints this one shape, so it's the piece's silhouette
// with the Q cut out of it (even-odd: the Q's outer edge cuts, its inner one
// fills back in).
layer("ic_launcher_monochrome", [
  pathElement({ fill: "#ffffff", evenOdd: true, data: `${outline.data} ${q.data}` }),
]);

// The notification icon: Android draws it in one colour, small, in the
// status bar, so it's the same silhouette, filling a 24 dp square but for
// a dp of margin all round.
const small = 22 / Math.max(width, height);
writeFileSync(
  path.join(out, "ic_notification.xml"),
  `${header}
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
${group([pathElement({ fill: "#ffffff", evenOdd: true, data: `${outline.data} ${q.data}` })], {
    scale: small,
    x: (24 - width * small) / 2,
    y: (24 - height * small) / 2,
  })}
</vector>
`
);

console.log(
  `Wrote logo.xml, ic_launcher_foreground.xml, ic_launcher_monochrome.xml and ic_notification.xml to ${path.relative(root, out)}`
);
