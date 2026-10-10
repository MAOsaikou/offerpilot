"""Preserve Archivo's variable axes and glyphs; split UI Latin from full Latin.

Run with FontTools + Brotli in a separate venv after pnpm install:
python tools/perf/subset-font.py
"""
from pathlib import Path
from fontTools import subset

root = Path(__file__).resolve().parents[2]
source = root / 'frontend/node_modules/@fontsource-variable/archivo/files/archivo-latin-wdth-normal.woff2'
target = root / 'frontend/src/shared/assets/fonts/archivo-ui-wdth-normal.woff2'
options = subset.Options()
options.flavor = 'woff2'
# Keep all layout features and variable weight/width; no design changes.
options.layout_features = ['*']
font = subset.load_font(str(source), options)
subsetter = subset.Subsetter(options=options)
subsetter.populate(unicodes=[*range(0x20, 0x7f), 0xa0, 0xb7, 0xd7, *range(0x2000, 0x2070)])
subsetter.subset(font)
target.parent.mkdir(parents=True, exist_ok=True)
subset.save_font(font, str(target), options)
print(f'{source.stat().st_size} -> {target.stat().st_size} bytes')
