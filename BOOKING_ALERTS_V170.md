# Barber & Beyond Manager v1.7.0 — Booking Alerts Preview

## What is implemented

- Opt-in **Settings → Notifications → New booking alerts**.
- When signed in to Website Bookings, checks new requests about every 45 seconds *while the app is open*.
- Android native system notification for new incoming pending bookings, generic lock-screen text only (no customer names, phone numbers, or other personal details).
- Tap the system notification to launch the Bookings page through the original splash.
- Pending booking count on the main dashboard.
- On explicit opt-in, refresh tokens are encrypted with an Android Keystore AES-256-GCM key stored locally. Signing out or disabling alerts deletes the encrypted session.
- WorkManager can check background requests about every **15 minutes or later**, subject to Android, Redmi/MIUI battery restrictions, connectivity, and manufacturer policies. It is **not** an instant, always-on push service.
- First successful check creates a baseline: it will not alert for bookings from before the setting was enabled.
- Offline sales, expenses, backup/restore, web price editing, barber image carousel and customer-facing website are unchanged.

## To test

1. Install the preview APK alongside v1.6.3; **do not uninstall previous versions**.
2. Open Settings → Notifications; enable booking alerts.
3. Go to Bookings and sign in to the shop's existing admin account.
4. While connected, leave the app open on Dashboard, and ask someone to submit a *test* booking through the public website.
5. Within roughly 45 seconds, you should see a new booking notification and an updated pending count. The phone's battery settings and Android permissions can affect delivery.
6. Tap the alert; it should open the Bookings screen. Confirm/Decline should continue to work.
7. To test background checks, leave the app normally (don't force-stop). An alert might arrive after 15 minutes or later; it is **not guaranteed instantly**.
8. Disable alerts and verify no new notifications appear. Sign out in Bookings to clear saved online credentials.
9. Confirm offline sales, price edits and room records still work.

## Important distinction: background polling is not cloud push

For instant background push when the app is closed or sleeping, this project still needs **Firebase Cloud Messaging (FCM)**:

- Set up a Firebase project under the business owner’s control and register the Android app package / correct preview identities.
- Configure the Firebase Android client SDK and secure registration of per-device tokens.
- Deploy a Supabase Edge Function triggered by new pending booking records, authenticate it server-side and send FCM HTTP v1 messages. Use service-account credentials only in server secrets, **never in this public GitHub repo or APK**.
- Permit pushes only for authorized administrators; validate recipient association and restrict event payloads to generic booking counts.
- Test lock-screen, background, app-closed, offline-to-online, doze and MIUI battery modes.

The current preview does *not* claim to provide FCM instant push. No Firebase credentials or user data were added to GitHub.

## Upgrade safety

The preview uses package suffix `.preview170` and is an independent installation. A future production update must use the original package identity, matching signing key and verified database migrations/backups.
