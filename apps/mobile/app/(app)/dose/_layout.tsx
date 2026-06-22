import { Stack } from "expo-router";
import type { ReactElement } from "react";

import { SyncStatusBadge } from "../../../src/sync/SyncStatusBadge";

export default function DoseLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen
        name="log"
        options={{ title: "Dose Log", headerRight: () => <SyncStatusBadge /> }}
      />
      <Stack.Screen
        name="history"
        options={{
          title: "Dose History",
          headerRight: () => <SyncStatusBadge />,
        }}
      />
    </Stack>
  );
}
