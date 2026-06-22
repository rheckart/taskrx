import { useClerk } from "@clerk/clerk-expo";
import type { ReactElement } from "react";
import { Button, StyleSheet, Text, View } from "react-native";

function SignOutButton(): ReactElement {
  const { signOut } = useClerk();

  return <Button onPress={() => void signOut()} title="Sign out" />;
}

export default function HomeScreen(): ReactElement {
  return (
    <View style={styles.container}>
      <Text>Welcome</Text>
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
  },
});
