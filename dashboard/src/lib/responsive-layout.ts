/** Matches Tailwind `lg` (1024px). Keep in sync with dashboard breakpoints. */
export const LG_MEDIA_QUERY = "(min-width: 1024px)";

/** Height of the mobile top bar (`AppShell` uses `h-12`). Used in `calc(100vh - …)` page shells. */
export const MOBILE_TOP_BAR_HEIGHT = "3rem";

/** Stacked panel height below `lg` (Mission/Control side panels, Ask co-pilot). */
export const MOBILE_STACKED_PANEL_HEIGHT = "60vh";

/** Fixed height for the system monitor chart area below `lg` so SVGs don't collapse. */
export const MOBILE_SYSTEM_MONITOR_HEIGHT = "560px";
