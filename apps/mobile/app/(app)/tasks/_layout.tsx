import { Stack } from "expo-router";
import type { ReactElement } from "react";

import { SyncStatusBadge } from "../../../src/sync/SyncStatusBadge";

export default function TasksLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen
        name="new"
        options={{
          title: "New Task",
          headerRight: () => <SyncStatusBadge />,
        }}
      />
    </Stack>
  );
}
