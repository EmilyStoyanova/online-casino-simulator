/**
 * Whether this browser can draw the wheel at all. Phaser's `AUTO` renderer uses WebGL when it can and
 * falls back to the Canvas renderer otherwise, so a 2D canvas context is the minimum. Checking WebGL
 * here as well would create (and waste) a GPU context just for the test.
 *
 * This module does not import Phaser: `RouletteWheel` calls it before loading the Phaser chunk,
 * because Phaser's own feature detection fails with an uncaught error while the module loads in a
 * browser without canvas (seen in Chrome during the W5-P01 review).
 */
export function canRenderWheel(doc: Document = document): boolean {
  try {
    return Boolean(doc.createElement('canvas').getContext('2d'))
  } catch {
    return false
  }
}
