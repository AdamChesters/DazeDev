// The terminal font choices. "cascadia" is bundled with the app; "system" is the phone's own
// fixed-width font.
export const bundledFont = "Cascadia Mono";
export function fontChoice(search) {
  return new URLSearchParams(search).get("font") === "system"
    ? "system"
    : "cascadia";
}
export function fontFamily(choice, system) {
  return choice === "system" ? system : `"${bundledFont}", ${system}`;
}
