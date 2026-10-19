# Voodoo visual identity

The homepage uses the owner's supplied embroidered pin-cushion Earth reference, edited with image generation to remove the gray background and balance the uppercase VOODOO / BROWSER seal. The bundled PNG has real alpha transparency. It is not a redraw of the browser interface.

The app UI is dark charcoal (`#25262B`), with muted cyan (`#8BBAC4`), dusty pink (`#CA96B5`), soft green (`#ACC19C`), seafoam (`#94B8AF`), and warm gold (`#D2B580`). It uses no neon bloom or high-intensity glow.

The separate interactive globe is rendered with Three.js 0.186.1. Its canvas-generated cloth color and bump maps combine plum fabric, seafoam land patches, quilting, and cross stitches. Each saved site's pin is a real mesh with a colored head and a needle. Pin positions are the user's layout, not their location, server locations, or VPN exit nodes. Initial positions are derived from the site's hostname to spread pins around the globe.

## Bundled dependencies

- Three.js: https://github.com/mrdoob/three.js — MIT license in `app/src/main/assets/vendor/THREE-LICENSE.txt`. The reproducible bundle is built from `web/globe.js` with `npm run build:web`.
- Natural Earth land polygons: https://github.com/nvkelso/natural-earth-vector/blob/master/geojson/ne_110m_land.geojson — public domain; https://www.naturalearthdata.com/about/terms-of-use/. Bundled as `vendor/land.geojson`; no runtime map request to a third party.

The artwork belongs with this project at the owner's request. Do not put private keys, personal workspace backups, or the signing recovery archive in the repository.
