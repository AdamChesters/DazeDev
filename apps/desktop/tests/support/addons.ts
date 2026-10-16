// Renderer-only xterm add-ons (fit, WebGL, images, ligatures) have nothing to
// do without a real browser renderer; these keep their public shape.
class Addon {
  activate() {}
  dispose() {}
}
export class FitAddon extends Addon {
  fit() {}
  proposeDimensions() {
    return undefined;
  }
}
export class WebglAddon extends Addon {
  onContextLoss() {
    return { dispose() {} };
  }
}
export class ImageAddon extends Addon {}
export class LigaturesAddon extends Addon {}
