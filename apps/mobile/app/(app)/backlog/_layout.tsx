import { Stack } from "expo-router";
import type { ReactElement } from "react";

import { SyncStatusBadge } from "../../../src/sync/SyncStatusBadge";

export default function BacklogLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen
        name="index"
        options={{
          title: "Backlog",
          headerRight: () => <SyncStatusBadge />,
        }}
      />
    </Stack>
  );
}
