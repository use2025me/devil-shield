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
- **Works on TVs.** Remote-friendly screens, appears on the Android TV and Fire TV home screen, and turns itself back on after a restart.

## How it works

Devil Shield runs a local VPN on the device itself. Only website lookups pass through it; all other traffic goes straight to the internet as normal. Nothing is sent to a Devil Media Group server.

Allowed lookups are passed to the network's own lookup service (with public fallbacks), so nothing changes apart from threats being blocked.

Protection lists update automatically every day:
- [HaGeZi Threat Intelligence Feeds](https://github.com/hagezi/dns-blocklists) (mini)
- Phishing and URLhaus lists via [malware-filter](https://gitlab.com/malware-filter)
- [URLhaus](https://urlhaus.abuse.ch/) by abuse.ch
- Family filter only: HaGeZi NSFW and gambling lists

## Known limits

- Only one VPN app can run at a time on Android. Using another VPN app switches Devil Shield off.
- Malware that ignores the device's lookup settings (hard-coded servers, or "Private DNS") can go around the shield. The health check flags Private DNS.
- Malware built into a device's own software can't be deleted by any app. Devil Shield cuts it off and explains the options.
- No app offers 100% protection.

## Building

Every push to `main` builds a signed APK with GitHub Actions and publishes it as the latest release. The app checks that release for updates.

The signing key is stored encrypted in `signing/release.jks.enc`. The build needs the `SIGNING_PASSWORD` secret under **Settings → Secrets and variables → Actions**. Keep that password safe: without it, future updates can't install over the current app.
