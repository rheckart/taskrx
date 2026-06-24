import { useSignIn } from "@clerk/clerk-expo";
import * as Linking from "expo-linking";
import { Link, useRouter } from "expo-router";
import type { ReactElement } from "react";
import { useState } from "react";
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";

const PRIMARY = "#007aff";
const MUTED = "#666";
const ERROR = "#dc2626";
const BORDER = "#d1d5db";

interface EmailCodeFactor {
  strategy: "email_code";
  emailAddressId: string;
  safeIdentifier: string;
  primary?: boolean;
}

interface EmailLinkFactor {
  strategy: "email_link";
  emailAddressId: string;
  safeIdentifier: string;
  primary?: boolean;
}

function getClerkErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return "Something went wrong. Please try again.";
}

export default function SignInScreen(): ReactElement {
  const { isLoaded, signIn, setActive } = useSignIn();
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [sentMethod, setSentMethod] = useState<"code" | "link" | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!isLoaded) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator size="large" color={PRIMARY} />
      </View>
    );
  }

  const handleSendCode = async (): Promise<void> => {
    setError(null);
    const trimmedEmail = email.trim();
    if (trimmedEmail.length === 0) {
      setError("Please enter your email address.");
      return;
    }

    setIsLoading(true);
    try {
      await signIn.create({ identifier: trimmedEmail });

      const factor = signIn.supportedFirstFactors?.find(
        (f): f is EmailCodeFactor => f.strategy === "email_code"
      );

      if (!factor) {
        setError("Email code sign-in is not available for this address.");
        return;
      }

      await signIn.prepareFirstFactor({
        strategy: "email_code",
        emailAddressId: factor.emailAddressId,
      });

      setSentMethod("code");
    } catch (err) {
      setError(getClerkErrorMessage(err));
    } finally {
      setIsLoading(false);
    }
  };

  const handleSendLink = async (): Promise<void> => {
    setError(null);
    const trimmedEmail = email.trim();
    if (trimmedEmail.length === 0) {
      setError("Please enter your email address.");
      return;
    }

    setIsLoading(true);
    try {
      await signIn.create({ identifier: trimmedEmail });

      const factor = signIn.supportedFirstFactors?.find(
        (f): f is EmailLinkFactor => f.strategy === "email_link"
      );

      if (!factor) {
        setError("Email link sign-in is not available for this address.");
        return;
      }

      await signIn.prepareFirstFactor({
        strategy: "email_link",
        emailAddressId: factor.emailAddressId,
        redirectUrl: Linking.createURL("/(auth)/sign-in"),
      });

      setSentMethod("link");
    } catch (err) {
      setError(getClerkErrorMessage(err));
    } finally {
      setIsLoading(false);
    }
  };

  const handleVerifyCode = async (): Promise<void> => {
    setError(null);
    const trimmedCode = code.trim();
    if (trimmedCode.length === 0) {
      setError("Please enter the passcode.");
      return;
    }

    setIsLoading(true);
    try {
      const result = await signIn.attemptFirstFactor({
        strategy: "email_code",
        code: trimmedCode,
      });

      if (result.createdSessionId !== null) {
        await setActive({ session: result.createdSessionId });
        router.replace("/(app)");
      }
    } catch (err) {
      setError(getClerkErrorMessage(err));
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === "ios" ? "padding" : "height"}
      style={styles.container}
    >
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
      >
        <Text style={styles.title}>Sign in to TaskRx</Text>
        <Text style={styles.subtitle}>
          Enter your email to receive a passcode or magic link.
        </Text>

        <TextInput
          accessibilityLabel="Email address"
          autoCapitalize="none"
          autoComplete="email"
          editable={!isLoading}
          keyboardType="email-address"
          onChangeText={setEmail}
          placeholder="you@example.com"
          placeholderTextColor="#9ca3af"
          returnKeyType="next"
          style={styles.input}
          value={email}
        />

        {error !== null ? <Text style={styles.error}>{error}</Text> : null}

        {sentMethod === "code" ? (
          <View style={styles.codeSection}>
            <Text style={styles.hint}>
              Enter the 6-digit code sent to your email.
            </Text>
            <TextInput
              accessibilityLabel="Passcode"
              autoCapitalize="none"
              autoComplete="one-time-code"
              editable={!isLoading}
              keyboardType="number-pad"
              maxLength={6}
              onChangeText={setCode}
              placeholder="000000"
              placeholderTextColor="#9ca3af"
              returnKeyType="done"
              style={styles.input}
              value={code}
            />
            <Pressable
              disabled={isLoading}
              onPress={() => void handleVerifyCode()}
              style={({ pressed }) => [
                styles.primaryButton,
                (pressed || isLoading) && styles.primaryButtonDisabled,
              ]}
            >
              {isLoading ? (
                <ActivityIndicator color="#fff" />
              ) : (
                <Text style={styles.primaryButtonText}>Verify</Text>
              )}
            </Pressable>
          </View>
        ) : sentMethod === "link" ? (
          <View style={styles.linkSection}>
            <Text style={styles.hint}>
              Check your email and tap the magic link to sign in.
            </Text>
            <Pressable
              disabled={isLoading}
              onPress={() => void handleSendLink()}
              style={({ pressed }) => [
                styles.secondaryButton,
                pressed && styles.secondaryButtonPressed,
              ]}
            >
              <Text style={styles.secondaryButtonText}>Resend magic link</Text>
            </Pressable>
          </View>
        ) : (
          <View style={styles.buttonGroup}>
            <Pressable
              disabled={isLoading}
              onPress={() => void handleSendCode()}
              style={({ pressed }) => [
                styles.primaryButton,
                (pressed || isLoading) && styles.primaryButtonDisabled,
              ]}
            >
              {isLoading ? (
                <ActivityIndicator color="#fff" />
              ) : (
                <Text style={styles.primaryButtonText}>Send passcode</Text>
              )}
            </Pressable>

            <Pressable
              disabled={isLoading}
              onPress={() => void handleSendLink()}
              style={({ pressed }) => [
                styles.secondaryButton,
                pressed && styles.secondaryButtonPressed,
              ]}
            >
              <Text style={styles.secondaryButtonText}>Send magic link</Text>
            </Pressable>
          </View>
        )}

        <Link href="/(auth)/sign-up" style={styles.link}>
          Don&apos;t have an account? Sign up
        </Link>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  buttonGroup: {
    gap: 12,
    width: "100%",
  },
  centered: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
  },
  codeSection: {
    gap: 12,
    width: "100%",
  },
  container: {
    flex: 1,
  },
  error: {
    color: ERROR,
    fontSize: 14,
    textAlign: "center",
    width: "100%",
  },
  hint: {
    color: MUTED,
    fontSize: 14,
    textAlign: "center",
  },
  input: {
    borderColor: BORDER,
    borderRadius: 8,
    borderWidth: 1,
    fontSize: 16,
    padding: 14,
    width: "100%",
  },
  link: {
    color: PRIMARY,
    fontSize: 16,
    fontWeight: "500",
    marginTop: 16,
  },
  linkSection: {
    alignItems: "center",
    gap: 16,
    width: "100%",
  },
  primaryButton: {
    alignItems: "center",
    backgroundColor: PRIMARY,
    borderRadius: 8,
    padding: 14,
    width: "100%",
  },
  primaryButtonDisabled: {
    opacity: 0.7,
  },
  primaryButtonText: {
    color: "#fff",
    fontSize: 16,
    fontWeight: "600",
  },
  scrollContent: {
    alignItems: "center",
    flexGrow: 1,
    gap: 16,
    justifyContent: "center",
    padding: 24,
  },
  secondaryButton: {
    alignItems: "center",
    borderColor: PRIMARY,
    borderRadius: 8,
    borderWidth: 1,
    padding: 14,
    width: "100%",
  },
  secondaryButtonPressed: {
    backgroundColor: "#e6f4fe",
  },
  secondaryButtonText: {
    color: PRIMARY,
    fontSize: 16,
    fontWeight: "600",
  },
  subtitle: {
    color: MUTED,
    fontSize: 16,
    marginBottom: 8,
    textAlign: "center",
  },
  title: {
    fontSize: 28,
    fontWeight: "700",
  },
});
