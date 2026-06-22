import { SignIn } from "@clerk/clerk-expo/web";
import type { ReactElement } from "react";
import { StyleSheet, View } from "react-native";

export default function SignInScreen(): ReactElement {
  return (
    <View style={styles.container}>
      <SignIn
        signUpUrl="/(auth)/sign-up"
        routing="path"
        path="/(auth)/sign-in"
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
    padding: 24,
  },
});
