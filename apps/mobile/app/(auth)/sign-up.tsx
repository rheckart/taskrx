import { useSignUp } from "@clerk/clerk-expo";
import { Link } from "expo-router";
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

function getClerkErrorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  return "Something went wrong. Please try again.";
}

export default function SignUpScreen(): ReactElement {
  const { isLoaded, signUp, setActive } = useSignUp();
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [isCodeSent, setIsCodeSent] = useState(false);
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
      await signUp.create({ emailAddress: trimmedEmail });
      await signUp.prepareEmailAddressVerification({ strategy: "email_code" });
      setIsCodeSent(true);
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
      setError("Please enter the verification code.");
      return;
    }

    setIsLoading(true);
    try {
      const result = await signUp.attemptEmailAddressVerification({
        code: trimmedCode,
      });

      if (result.createdSessionId !== null) {
        await setActive({ session: result.createdSessionId });
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
        <Text style={styles.title}>Create your account</Text>
        <Text style={styles.subtitle}>
          Enter your email to receive a verification code.
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

        {isCodeSent ? (
          <View style={styles.codeSection}>
            <Text style={styles.hint}>
              Enter the 6-digit code sent to your email.
            </Text>
            <TextInput
              accessibilityLabel="Verification code"
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
        ) : (
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
              <Text style={styles.primaryButtonText}>
                Send verification code
              </Text>
            )}
          </Pressable>
        )}

        <Link href="/(auth)/sign-in" style={styles.link}>
          Already have an account? Sign in
        </Link>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
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
