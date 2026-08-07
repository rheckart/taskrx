import type { ReactElement } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";

import { useNotificationPermission } from "../notifications/permission-provider";

/**
 * Says out loud that reminders cannot arrive.
 *
 * This is the "handle refusal honestly" half of issue #20. The request itself
 * is upstream in `NotificationPermissionProvider`; what this adds is the state
 * the app had no way to express at all — alarms armed, alarms firing, every
 * notification discarded by Android, and nothing on screen to say so. A person
 * in that state used to find out by missing doses.
 *
 * It renders on the Today screen because that is where somebody looks to see
 * what is coming, and a claim about what is coming is exactly what a dropped
 * notification falsifies.
 *
 * Two severities, styled apart, because they are different sentences: a
 * `blocking` gap means nothing reaches you, a `degraded` one means it reaches
 * you late. Painting both red would teach people to scroll past the one that
 * means something. The wording of each lives in `@taskrx/shared`, so this file
 * decides nothing about what is said — only how it looks.
 */
export function NotificationPermissionBanner(): ReactElement | null {
  const permission = useNotificationPermission();
  const gap = permission?.state?.gap;

  if (permission === null || gap === undefined || gap === null) {
    return null;
  }

  const blocking = gap.severity === "blocking";

  return (
    <View
      style={[styles.banner, blocking ? styles.blocking : styles.degraded]}
      accessibilityRole="alert"
      accessibilityLabel={`${gap.title}. ${gap.body}`}
    >
      <Text style={[styles.title, blocking ? styles.titleBlocking : null]}>
        {gap.title}
      </Text>
      <Text style={styles.body}>{gap.body}</Text>
      <Pressable
        hitSlop={8}
        onPress={() => {
          permission.resolve(gap.remedy);
        }}
        accessibilityRole="button"
        accessibilityLabel={gap.remedyLabel}
      >
        <Text style={styles.action}>{gap.remedyLabel}</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  action: {
    color: "#007aff",
    fontSize: 15,
    fontWeight: "600",
    marginTop: 8,
  },
  banner: {
    borderLeftWidth: 3,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  blocking: {
    backgroundColor: "#fdecea",
    borderLeftColor: "#c00",
  },
  body: {
    color: "#444",
    fontSize: 14,
    marginTop: 4,
  },
  degraded: {
    backgroundColor: "#fdf3d8",
    borderLeftColor: "#8a6d00",
  },
  title: {
    fontSize: 15,
    fontWeight: "700",
  },
  titleBlocking: {
    color: "#c00",
  },
});
