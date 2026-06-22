import { SignUp } from "@clerk/clerk-expo/web";
import type { ReactElement } from "react";
import { StyleSheet, View } from "react-native";

export default function SignUpScreen(): ReactElement {
  return (
    <View style={styles.container}>
      <SignUp
        signInUrl="/(auth)/sign-in"
        routing="path"
        path="/(auth)/sign-up"
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
