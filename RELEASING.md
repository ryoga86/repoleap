# Releasing RepoLeap

Interne Checkliste für Veröffentlichungen im [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34545-repoleap).

## Stand

| Punkt          | Wert                                                                                              |
|----------------|---------------------------------------------------------------------------------------------------|
| Name / ID      | `RepoLeap` / `de.pdenis.repoleap` (Marketplace-ID `34545`)                                        |
| Version        | `1.2.0` (`gradle.properties` → `pluginVersion`)                                                   |
| Kompatibilität | since-build 252 (2025.2), kein until-build                                                        |
| Vendor         | Peter Denis, mail@pdenis.de (`gradle.properties` → `pluginVendor` + `plugin.xml`)                 |
| Lizenz         | MIT (`LICENSE`)                                                                                   |
| Signatur       | Schlüssel in `~/.jetbrains-signing/repoleap/`, Build liest ihn aus `~/.gradle/gradle.properties` |
| CI             | `.github/workflows/build.yml` + `release.yml`                                                     |

## Branches

- **`main`** = veröffentlichter Stand. README und Banner auf `main` sind das, was Besucher und die Marketplace-Seite
  sehen. Hier nur Releases (Merge von `develop`) und Doku zum aktuellen Stand.
- **`develop`** = nächste Version. Hier wird entwickelt; Dependabot-PRs gehen ebenfalls hierhin
  (`target-branch` in `.github/dependabot.yml`).
- CI (`build.yml`) läuft bei Pushes auf beide Branches und bei jedem PR. Ein Draft-Release entsteht nur auf `main`.

## Offen

Nur im Web-Formular der Plugin-Seite möglich (Login nötig):

- [ ] Plugin-Seite: Issue Tracker eintragen (`https://github.com/ryoga86/repoleap/issues`) – in der Marketplace-API ist
      `bugtrackerUrl` noch leer
- [ ] Screenshots für 1.2.0 ergänzen (empfohlen 1280×800, keine winzige Schrift): Tags (`#wo`), Klonen per URL,
      Hervorhebung + Trennzeichen in den Settings. Zwei ältere Screenshots sind bereits hochgeladen.

Bereits erledigt: Source-Code-Link, Ko-fi unter *Monetization*, öffentlicher Name `Peter Denis`, Non-Trader,
einmalige Einrichtung der Actions (unten).

**1.2.0** ist am 2026-11-03 hochgeladen und wartet auf die Prüfung durch JetBrains (Marketplace-API:
`hasUnapprovedUpdate`; die neueste öffentliche Version ist solange 1.1.1). Ohne Rückmeldung nach 3–4 Werktagen:
marketplace@jetbrains.com.

## Folge-Releases über GitHub Actions

### Einmalige Einrichtung

1. *Settings | Actions | General | Workflow permissions*: **Read and write permissions** und
   **Allow GitHub Actions to create and approve pull requests** aktivieren (für Draft-Releases und Changelog-PRs).
2. Repository-Secrets anlegen (*Settings | Secrets and variables | Actions*):

| Secret                 | Inhalt                                                             |
|------------------------|--------------------------------------------------------------------|
| `PUBLISH_TOKEN`        | Marketplace-Token (https://plugins.jetbrains.com/author/me/tokens) |
| `CERTIFICATE_CHAIN`    | Inhalt von `~/.jetbrains-signing/repoleap/chain.crt`               |
| `PRIVATE_KEY`          | Inhalt von `~/.jetbrains-signing/repoleap/private_encrypted.pem`   |
| `PRIVATE_KEY_PASSWORD` | Inhalt von `~/.jetbrains-signing/repoleap/password.txt`            |

### Ablauf pro Release

1. Auf `develop`: Änderungen unter `## [Unreleased]` in `CHANGELOG.md` eintragen, `pluginVersion` in
   `gradle.properties` erhöhen (`1.1.0`; Pre-Releases wie `1.1.0-beta.1` landen automatisch im Channel `beta`).
2. PR `develop` → `main` öffnen, CI abwarten, mergen → `build.yml` legt auf `main` ein **Draft-Release** an.
3. Draft auf GitHub prüfen und veröffentlichen (nur als `ryoga86` eingeloggt sichtbar; **nicht** über „Draft a new
   release“ ein zweites anlegen) → `release.yml` patcht den Changelog, signiert, lädt in den Marketplace hoch, hängt
   die ZIPs ans Release und öffnet einen PR, der nur `CHANGELOG.md` ändert → mergen.
4. `main` zurück nach `develop` holen, damit der gepatchte Changelog auch dort ist:
   ```bash
   git checkout develop && git pull && git merge origin/main && git push
   ```

Jede neue Version wird von JetBrains erneut geprüft, bevor sie öffentlich ist.

### Alternativ lokal

```bash
./gradlew clean check                 # Tests
./gradlew verifyPlugin                # Plugin Verifier gegen empfohlene IDEs (lädt mehrere GB)
./gradlew patchChangelog              # [Unreleased] -> [x.y.z] - <Datum>
./gradlew publishPlugin               # Token: repoleap.publishToken=perm:… in ~/.gradle/gradle.properties
git commit -am "Release x.y.z" && git tag x.y.z && git push && git push --tags
```

## Hinweise

- **Nicht mehr ändern:** Plugin-ID, Action-ID (`de.pdenis.repoleap.OpenRepository`) und
  `@State(name = "RepoLeapSettings")` – sonst verlieren Nutzer Einstellungen bzw. eigene Shortcuts.
- **Banner:** Die Plugin-Beschreibung lädt die Bilder live von `main`
  (`raw.githubusercontent.com/ryoga86/repoleap/main/docs/…`): `banner.png` (plugin.xml von 1.0.0) und
  `banner-github.png` (ab 1.1.0, auch im README). `banner-marketplace.png` wird derzeit nicht verwendet. Auf `main`
  weder umbenennen noch löschen, solange eine
  Version sie verwendet. Änderungen am Bild werden mit dem Merge nach `main` sofort sichtbar – auch für bereits
  veröffentlichte Versionen. Repo public lassen, Branch `main` nicht umbenennen.
- **Signatur-Schlüssel** (`private_encrypted.pem`, `chain.crt`, `password.txt`) im Passwort-Manager sichern, nie
  committen. Zertifikat gültig bis 09/2036.
- **Neuer Clone:** Global ist die Arbeits-Adresse konfiguriert, daher im Repo setzen:
  `git config user.name "Peter Denis" && git config user.email "13853329+ryoga86@users.noreply.github.com"`
