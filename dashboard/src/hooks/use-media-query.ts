import { useEffect, useState } from "react";

/**
 * Subscribe to a CSS media query. Returns `false` during SSR and until the first
 * client match so layout doesn't flash incorrectly on hydration.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(false);

  useEffect(() => {
    const media = window.matchMedia(query);
    const sync = () => setMatches(media.matches);
    sync();
    media.addEventListener("change", sync);
    return () => media.removeEventListener("change", sync);
  }, [query]);

  return matches;
}
