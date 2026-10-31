// ─────────────────────────────────────────────────────────────
//  Student OS — brand mark (single source of truth)
//
//  Every icon asset (favicon, PWA icons, iOS icons, splash screens)
//  is generated from this file so the logo can never drift again.
//
//  The mark: a violet rounded tile with the white graduation glyph.
//  Geometry is traced from the shipped app icon (512 × 512 box):
//    • circle  cx 256 cy 224 r 96
//    • collar  176,216 → 336,336  (a cut-out that separates cap from tile)
//    • board   192,232 → 320,320
//    • bar     192,336 → 320,371
// ─────────────────────────────────────────────────────────────

export const BRAND_SIZE = 512
export const BRAND_TILE = '#6366F1'
/** Matches --background / manifest background_color. */
export const BRAND_PLATE = '#0a0a14'

const GLYPH = `<circle cx="256" cy="224" r="96" fill="#FFFFFF"/>
  <rect x="176" y="216" width="160" height="120" fill="${BRAND_TILE}"/>
  <rect x="192" y="232" width="128" height="89" fill="#FFFFFF"/>
  <rect x="192" y="336" width="128" height="35" fill="#FFFFFF"/>`

/** Markup without the outer <svg> tag, for embedding inside other SVGs. */
export const BRAND_INNER = `<rect width="${BRAND_SIZE}" height="${BRAND_SIZE}" rx="105" fill="${BRAND_TILE}"/>
  ${GLYPH}`

/**
 * Standalone brand-mark SVG.
 *
 * @param {object} [options]
 * @param {boolean} [options.plate]   Paint an opaque plate behind the rounded
 *                                    tile (required for launcher icons and
 *                                    Apple touch icons, which must be opaque).
 * @param {boolean} [options.rounded] Keep the rounded corners. Pass false for
 *                                    full-bleed square icons the OS masks itself.
 */
export function brandSvg({ plate = false, rounded = true } = {}) {
  const tile = rounded
    ? `<rect width="${BRAND_SIZE}" height="${BRAND_SIZE}" rx="105" fill="${BRAND_TILE}"/>`
    : `<rect width="${BRAND_SIZE}" height="${BRAND_SIZE}" fill="${BRAND_TILE}"/>`

  const shapes = plate
    ? [`<rect width="${BRAND_SIZE}" height="${BRAND_SIZE}" fill="${BRAND_PLATE}"/>`, tile, GLYPH]
    : [tile, GLYPH]

  return `<svg width="${BRAND_SIZE}" height="${BRAND_SIZE}" viewBox="0 0 ${BRAND_SIZE} ${BRAND_SIZE}" fill="none" xmlns="http://www.w3.org/2000/svg">
  ${shapes.join('\n  ')}
</svg>
`
}
