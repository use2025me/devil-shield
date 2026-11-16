# Devil Shield

**Calm, simple protection against malware, scams and spying for TV boxes, phones and tablets.**
By Devil Media Group.

## What it does

- **Blocks known threats.** Every website lookup on the device is checked against public security lists of malware, scam, phishing and botnet sites. Known threats are blocked; everything else works normally.
- **Shows who's calling home.** The Activity screen lists what was blocked and which app tried to reach it, so hidden malware stands out.
- **Health check.** Looks for the usual warning signs: apps that contacted known threats, remote debugging (ADB) switched on, apps with device-admin or screen-reading control, camera/microphone apps from outside an app store, box models named in public malware reports, old security updates, and settings that go around the shield.
- **Helps clean up.** One tap opens Android's uninstall screen for a problem app. For software built into the device, it explains the options step by step and keeps that software cut off.
- **Trusted sites.** If anything is ever blocked by mistake, trust it with one tap.
- **Optional family filter.** Adds adult and gambling sites, protected by a PIN. Off by default.
- **Calm by design.** No scary pop-ups. One clear status: "You're protected".
- **Works on TVs.** Remote-friendly screens, appears on the Android TV and Fire TV home screen, and turns itself back on after a restart or update.
- **Private lookups.** Allowed lookups are encrypted (DNS over HTTPS via Cloudflare, with Google as backup), falling back to the network's own service when needed. Can be turned off in Settings.
- **New-app check.** Newly installed apps from outside an app store that ask for strong abilities get a gentle notice and appear in the health check.
- **Quick switch and widget.** A Devil Shield switch in the pull-down quick settings, and a home-screen widget showing "Protected".
- **Welcome screen** and an optional **weekly summary** notice.
- **App controls.** Cut any app off from the internet (fully in VPN mode; by blocking its lookups otherwise). Ideal for hidden apps built into a box.
- **Network scan.** Finds devices on the home network and flags open Telnet, open ADB, cameras and router tips. Checks a few common ports only; never tries to log in.
- **Open Wi-Fi protection.** Warns on Wi-Fi with no password and switches the imported VPN on automatically (Android 12+).
- **Light on memory.** Lists are stored as 8-byte fingerprints, so 200,000 sites use about 1.6 MB.

## Built-in VPN (version 2)

Import a WireGuard VPN file (.conf) from Surfshark, Proton VPN, Mullvad, Windscribe, IVPN or your own server under **Your VPN**. Devil Shield then carries the whole connection through that VPN while still blocking threats and keeping the Activity log. Allowed lookups go through the VPN to its own lookup service. IPv6 traffic is routed into the tunnel so nothing leaks around it. The WireGuard protocol is implemented in Kotlin (with Bouncy Castle for the encryption) and every build tests it against a real WireGuard server.

## How it works

Devil Shield runs a local VPN on the device itself. Only website lookups pass through it; all other traffic goes straight to the internet as normal. Nothing is sent to a Devil Media Group server.

Allowed lookups are sent encrypted (private lookups), or to the network's own lookup service if private lookups are off or unavailable.

Protection lists update automatically every day:
- [HaGeZi Threat Intelligence Feeds](https://github.com/hagezi/dns-blocklists) (mini)
- Phishing and URLhaus lists via [malware-filter](https://gitlab.com/malware-filter)
- [URLhaus](https://urlhaus.abuse.ch/) by abuse.ch
- Family filter only: HaGeZi NSFW and gambling lists

## Known limits

- Only one VPN app can run at a time on Android. Using another VPN app switches Devil Shield off; import that VPN's WireGuard file instead to have both.
- Malware that ignores the device's lookup settings (hard-coded servers, or "Private DNS") can go around the shield. The health check flags Private DNS.
- Malware built into a device's own software can't be deleted by any app. Devil Shield cuts it off and explains the options.
- No app offers 100% protection.

## Building

Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest release. The app checks that release for updates.

The signing key is stored encrypted in `signing/release.jks.enc`. The build needs the `SIGNING_PASSWORD` secret under **Settings → Secrets and variables → Actions**. Keep that password safe: without it, future updates can't install over the current app.
