# AirTooth

Android-app, der sender telefonens lyd (fx YouTube i browseren) via AirPlay 1 til flere højttalere på én gang,
så de spiller i takt med hinanden.

## Sådan virker den
- Finder AirPlay-højttalere på WiFi (`_raop._tcp`).
- Opfanger lyden fra andre apps med Androids *Audio Playback Capture* (kræver tilladelse til "optagelse/casting").
- Sender den samme lydstrøm med de samme tidsstempler til alle højttalere. Højttalerne stiller deres ur efter telefonen,
  og alle afspiller hver pakke 2 sekunder efter den er sendt. Derfor spiller de synkront.

## Byg
Bygges automatisk af GitHub Actions ved hvert push (`.github/workflows/build.yml`).
APK'en ligger under **Releases** (`AirTooth.apk`).

## Brug
1. Installér `AirTooth.apk` på telefonen (tillad "Installér ukendte apps" for browseren/Mine filer).
2. Åbn AirTooth, vælg højttalerne, tryk **Start**.
3. Tryk *Start* i Android-dialogen (kun første gang).
4. Gå til browseren og spil musik.

**Stop** afbryder højttalerne, men AirTooth bliver *klar*, så næste Start spiller med det samme uden ny godkendelse.
**Afslut** lukker helt og slipper godkendelsen.

Kendte begrænsninger:
- Ca. 2 sekunders forsinkelse fra play til lyd (som AirPlay på iPhone).
- Nogle apps (fx Netflix) blokerer optagelse af deres lyd.
- Hvis noget ikke virker, så kig i loggen nederst i appen.
