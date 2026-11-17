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
 *
 * The second entry is the desktop Companion (ADR 0017). It signs in through the browser to a
 * Clerk OAuth application (public, PKCE) and sends that application's OIDC ID token. The
 * token has the same issuer and `sub` (the Clerk user id) as the phone's `convex` template
 * token, so both devices are one user. Its `aud` is the OAuth client id, which is what
 * `applicationID` checks. Set `CLERK_DESKTOP_CLIENT_ID` to that client id. Unset, the desktop
 * is not accepted.
 */
const issuer = process.env.CLERK_JWT_ISSUER_DOMAIN || "";
const desktopClientId = process.env.CLERK_DESKTOP_CLIENT_ID;

export default {
  providers: [
    { domain: issuer, applicationID: "convex" },
    ...(desktopClientId
      ? [{ domain: issuer, applicationID: desktopClientId }]
      : []),
  ],
};
