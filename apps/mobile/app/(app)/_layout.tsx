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
      {/*
        Titled for the question it answers rather than for the domain word. The
        glossary's term is Adherence and the screen is named for it in code, but
        the heading a person reads is the thing they came to find out.
      */}
      <Stack.Screen name="adherence" options={{ title: "How it's going" }} />
      {/*
        Titled for the question rather than for the domain word, the same way
        Adherence is. The glossary's term is Refill and the route is named for
        it; "Supplies" is what someone is checking when they open it, and it
        covers the medications that are not running out as well as the ones that
        are.
      */}
      <Stack.Screen name="refill" options={{ title: "Supplies" }} />
    </Stack>
  );
}
