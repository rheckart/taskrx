import { Stack } from "expo-router";
import type { ReactElement } from "react";

import { SyncStatusBadge } from "../../src/sync/SyncStatusBadge";

export default function AppLayout(): ReactElement {
  return (
    <Stack>
      <Stack.Screen name="index" options={{ title: "TaskRx" }} />
      {/*
        Named here rather than left to the default, because the default title
        for this route would be the word "add". It is the primary creation
        surface for both Kinds, so it is titled for what a person is doing
        rather than for the file it lives in, and it carries the sync badge the
        other two creation screens carry.
      */}
      <Stack.Screen
        name="add"
        options={{
          title: "Add a reminder",
          headerRight: () => <SyncStatusBadge />,
        }}
      />
    </Stack>
  );
}
