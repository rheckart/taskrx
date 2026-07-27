// Custom entry point. The notifee background handler must be registered here
// rather than in app/_layout.tsx: a headless JS context (started when a
// notification fires with the app process dead) evaluates this bundle but
// never mounts expo-router. Import order matters — the handler registration
// must run before the router entry.
import "./src/notifications/background-handler";

import "expo-router/entry";
