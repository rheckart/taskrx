/**
 * Clerk JWT authentication configuration for Convex.
 *
 * This tells Convex to accept JWTs issued by Clerk for this application.
 * The `domain` must be Clerk's JWT issuer for your account.
 *
 * Where to find it:
 * 1. Open the Clerk Dashboard: https://dashboard.clerk.com
 * 2. Select your application.
 * 3. Go to "Configure" → "JWT Templates" (or "API Keys").
 * 4. The issuer domain is shown as something like:
 *    `https://your-app.clerk.accounts.dev`
 *    (or a custom domain if you have one configured).
 * 5. Paste that value into the `CLERK_JWT_ISSUER_DOMAIN` environment variable
 *    for this Convex deployment.
 *
 * See the official Convex + Clerk guide:
 * https://docs.convex.dev/auth/clerk
 */
export default {
  providers: [
    {
      domain: process.env.CLERK_JWT_ISSUER_DOMAIN || "",
      applicationID: "convex",
    },
  ],
};
