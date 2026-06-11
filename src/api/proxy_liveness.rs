//! Per-mission liveness signals from the builtin LLM proxy.
//!
//! Harness runners watch their CLI's stdout/stderr to decide whether a turn is
//! stuck, but thinking models (MiniMax-M3, GLM, ...) can spend minutes inside a
//! reasoning segment during which OpenCode emits no JSON events at all. The
//! proxy, however, sees the upstream tokens streaming the whole time. This
//! registry lets the proxy record "mission X received an upstream chunk just
//! now" so runner watchdogs can distinguish "model is still generating" from
//! "process is genuinely hung".
//!
//! Attribution: the per-workspace builtin provider config sends an
//! `x-sandboxed-mission-id` header with every proxy request (see
//! `ensure_opencode_provider_for_model`). Requests without the header (router
//! traffic, chain tests, older workspace configs) are simply not tracked.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use uuid::Uuid;

/// Header carrying the mission id on builtin proxy requests.
pub const MISSION_ID_HEADER: &str = "x-sandboxed-mission-id";

/// Entries older than this are pruned opportunistically on writes; a turn that
/// has been silent for an hour is long past any watchdog decision.
const PRUNE_AFTER: Duration = Duration::from_secs(3600);

static REGISTRY: OnceLock<Mutex<HashMap<Uuid, Instant>>> = OnceLock::new();

fn registry() -> &'static Mutex<HashMap<Uuid, Instant>> {
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Record proxy activity (request accepted or upstream chunk received) for a
/// mission.
pub fn note_activity(mission_id: Uuid) {
    if let Ok(mut map) = registry().lock() {
        map.insert(mission_id, Instant::now());
        if map.len() > 64 {
            map.retain(|_, ts| ts.elapsed() < PRUNE_AFTER);
        }
    }
}

/// Time since the proxy last saw activity for this mission, if any.
pub fn time_since_activity(mission_id: Uuid) -> Option<Duration> {
    registry()
        .lock()
        .ok()
        .and_then(|map| map.get(&mission_id).map(|ts| ts.elapsed()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn note_then_query_roundtrip() {
        let id = Uuid::new_v4();
        assert!(time_since_activity(id).is_none());
        note_activity(id);
        let elapsed = time_since_activity(id).expect("activity recorded");
        assert!(elapsed < Duration::from_secs(5));
    }
}
