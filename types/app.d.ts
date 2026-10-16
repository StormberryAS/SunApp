// Expando the app sets on its own dropdown list (app.js onCityInput). Never served.
interface HTMLUListElement {
  /** The cities currently listed, in display order, for keyboard selection. */
  _matches?: City[];
}
