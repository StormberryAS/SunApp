// Expando the app sets on its own dropdown list (ts/app.ts onCityInput). Never served.
interface HTMLUListElement {
  /** The cities currently listed, in display order, for keyboard selection. */
  _matches?: City[];
}
