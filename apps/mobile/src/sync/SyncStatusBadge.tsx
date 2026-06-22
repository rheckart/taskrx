import type { ReactElement } from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";

import { useSyncStatus } from "./pull";

/**
 * Small header badge that displays the current sync state.
 *
 * - synced: green check
 * - pending: yellow clock
 * - syncing: blue spinner
 * - offline: gray cloud-off
 * - error: red alert
 */

export function SyncStatusBadge(): ReactElement | null {
  const { status, isSignedIn } = useSyncStatus();

  if (!isSignedIn) {
    return null;
  }

  switch (status) {
    case "synced":
      return (
        <View style={[styles.badge, { backgroundColor: "#e6f9ee" }]}>
          <Text style={[styles.icon, { color: "#34c759" }]}>✓</Text>
        </View>
      );
    case "pending":
      return (
        <View style={[styles.badge, { backgroundColor: "#fff8e6" }]}>
          <Text style={[styles.icon, { color: "#ff9500" }]}>⏱</Text>
        </View>
      );
    case "syncing":
      return (
        <View style={[styles.badge, { backgroundColor: "#e6f4fe" }]}>
          <ActivityIndicator size="small" color="#007aff" />
        </View>
      );
    case "offline":
      return (
        <View style={[styles.badge, { backgroundColor: "#f2f2f7" }]}>
          <Text style={[styles.icon, { color: "#8e8e93" }]}>☁</Text>
        </View>
      );
    case "error":
      return (
        <View style={[styles.badge, { backgroundColor: "#ffe6e6" }]}>
          <Text style={[styles.icon, { color: "#c00" }]}>!</Text>
        </View>
      );
  }
}

const styles = StyleSheet.create({
  badge: {
    alignItems: "center",
    borderRadius: 12,
    height: 24,
    justifyContent: "center",
    marginRight: 8,
    width: 24,
  },
  icon: {
    fontSize: 14,
    fontWeight: "700",
  },
});
