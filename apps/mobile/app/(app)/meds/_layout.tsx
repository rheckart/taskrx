import { Stack } from "expo-router";
import type { ReactElement } from "react";

import { SyncStatusBadge } from "../../../src/sync/SyncStatusBadge";

export default function MedsLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen
        name="index"
        options={{
          title: "My Medications",
          headerRight: () => <SyncStatusBadge />,
        }}
      />
      <Stack.Screen name="search" options={{ title: "Search RxNorm" }} />
      <Stack.Screen name="new" options={{ title: "New Medication" }} />
    </Stack>
  );
}
