# v1.6.2 offline hero slider sources

All slider backgrounds are bundled into `app/src/main/res/drawable-nodpi/` as WebP images at 1440×620. They load without network access.

- `hero_grooming.webp` — Los Muertos Crew, [Pexels 8867490](https://www.pexels.com/photo/a-barber-putting-shaving-cream-on-a-client-8867490/)
- `hero_fade.webp` — Gustavo Fring, [Pexels 7447148](https://www.pexels.com/photo/person-using-a-hair-clipper-7447148/)
- `hero_studio.webp` — Kawê Rodrigues, [Pexels 19664872](https://www.pexels.com/photo/empty-seats-in-barbershop-19664872/)

Licensed for free commercial application use under the Pexels License (https://www.pexels.com/license/). Attribution is appreciated but not required. These are illustrative stock photographs, not photos of the shop, its actual staff or customers, and the people depicted do not endorse Barber & Beyond.

## Slider behavior

- Three native Compose `HorizontalPager` pages; swiping works in either direction.
- Automatic advancement every 5 seconds when Dashboard is visible, with reduced interference during manual gestures.
- Three interactive indicator dots (gold = active).
- Date and Shop Overview overlay remain consistent; each photo has a relevant descriptor.
- No changes to database, booking sync, pricing or site.

## Quality and performance

Do not replace the high-resolution bundled WebP with tiny thumbnails. When swapping any image, provide source >= 1400 pixels wide and regenerate at 1440×620; keep the horizontal left side dark enough for readable headings. Any future photo must be licensed for app use.

Release preview package `ke.elgonlabs.barberbeyond.manager.preview162` installs alongside earlier previews. A production update needs a verified matching release signing key and backups.
