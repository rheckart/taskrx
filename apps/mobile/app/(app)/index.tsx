import { useClerk, useUser } from "@clerk/clerk-expo";
import { Link } from "expo-router";
import type { ReactElement } from "react";
import { Button, StyleSheet, Text, View } from "react-native";

function SignOutButton(): ReactElement {
  const { signOut } = useClerk();

  return <Button onPress={() => void signOut()} title="Sign out" />;
}

function UserBadge(): ReactElement {
  const { isLoaded, user } = useUser();

  if (!isLoaded) {
    return <Text style={styles.muted}>Loading user…</Text>;
  }
  if (user === null) {
    return <Text style={styles.muted}>Not signed in.</Text>;
  }

  const email = user.primaryEmailAddress?.emailAddress;
  const displayName =
    user.fullName ?? user.username ?? user.firstName ?? email ?? "User";

  return (
    <View style={styles.userBadge}>
      <Text style={styles.userName}>{displayName}</Text>
      {email !== undefined ? (
        <Text style={styles.userEmail}>{email}</Text>
      ) : null}
    </View>
  );
}

export default function HomeScreen(): ReactElement {
  return (
    <View style={styles.container}>
      <Text style={styles.title}>TaskRx</Text>
      <UserBadge />
      <Link href="/meds" style={styles.link}>
        View Medications
      </Link>
      <Link href={"/debug/notifications"} style={styles.link}>
        Notification Debug
      </Link>
      <SignOutButton />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: "center",
    flex: 1,
    gap: 16,
    justifyContent: "center",
    padding: 24,
  },
  link: {
    color: "#007aff",
    fontSize: 16,
    fontWeight: "500",
  },
  muted: {
    color: "#666",
  },
  title: {
    fontSize: 28,
    fontWeight: "700",
  },
  userBadge: {
    alignItems: "center",
  },
  userEmail: {
    color: "#666",
    fontSize: 14,
    marginTop: 2,
  },
  userName: {
    fontSize: 16,
    fontWeight: "500",
  },
});
