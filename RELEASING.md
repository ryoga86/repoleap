# Releasing RepoLeap

Interne Checkliste für Veröffentlichungen im [JetBrains Marketplace](https://plugins.jetbrains.com).

## Stand

| Punkt                               | Status                                                                  |
|-------------------------------------|-------------------------------------------------------------------------|
| Name / ID                           | `RepoLeap` / `de.pdenis.repoleap` (ID nach 1. Upload fix!)      |
| Marketplace                         | ID `34545`, https://plugins.jetbrains.com/plugin/34545-repoleap – für eine eigene Website gibt es den Install-Button als `<iframe frameborder="none" width="245px" height="48px" src="https://plugins.jetbrains.com/embeddable/install/34545"></iframe>` (GitHub und die Plugin-Beschreibung zeigen keine iframes, im README stehen deshalb Badges) |
| Vendor                              | Peter Denis, mail@pdenis.de, https://github.com/ryoga86/repoleap (`gradle.properties` → `pluginVendor` + `plugin.xml`) |
| Version                             | `1.0.0` (`gradle.properties` → `pluginVersion`)                         |
| Lizenz / EULA                       | MIT (`LICENSE`)                                                         |
| Kompatibilität                      | since-build 252 (2025.2), kein until-build; alle IntelliJ-basierten IDEs |
| Default-Shortcut                    | ⌥⌘I (macOS) / Alt+Shift+P (Win/Linux) – auf macOS frei; Alt+Shift+P nur bei Mercurial-Plugin und Eclipse-/Emacs-Keymap doppelt |
| Signatur                            | Schlüssel in `~/.jetbrains-signing/repoleap/`, Build liest ihn aus `~/.gradle/gradle.properties` |
| CI                                  | `.github/workflows/build.yml` + `release.yml`                           |
| Icon                                | `pluginIcon.svg` (für hell und dunkel), Vorlage in `dev/design/3.svg` (nur lokal, `dev/` ist git-ignoriert) |
| Banner                              | `docs/banner.png` – im README relativ, in der Beschreibung über `raw.githubusercontent.com/ryoga86/repoleap/main/docs/banner.png` (erst nach dem Push sichtbar) |
| Screenshots                         | **fehlen**                                                              |

## 1. Einmalige Vorbereitung

1. **Git-Identität für dieses Repo setzen** – global ist die Arbeits-Adresse konfiguriert, die sonst in der
   öffentlichen Commit-Historie landet. Öffentlich erscheinen sollen der Name `Peter Denis` und die
   GitHub-noreply-Adresse (GitHub → *Settings → Emails*: „Keep my email addresses private“ aktivieren, dort steht
   die Adresse im Format `<ID>+ryoga86@users.noreply.github.com`):
   ```bash
   git config user.name "Peter Denis"
   git config user.email "<ID>+ryoga86@users.noreply.github.com"
   ```
2. **GitHub-Repo** `ryoga86/repoleap` anlegen (public, *ohne* README/License/.gitignore), dann:
   ```bash
   git add -A && git commit -m "Initial commit" && git push -u origin main
   ```
   Unter *Settings | Actions | General | Workflow permissions*: **Read and write permissions** und
   **Allow GitHub Actions to create and approve pull requests** aktivieren (für Draft-Releases und Changelog-PRs).
3. **Icon** (erledigt): `dev/design/3.svg` ist eine A4-Seite mit eingebettetem 2048×2048-PNG. Daraus wurde das Motiv
   ausgeschnitten, auf 144×144 px verkleinert (= 36 px bei 4-facher Auflösung, reicht für 80 px auf Retina),
   mit `pngquant` (128 Farben) und `oxipng` auf ca. 7 kB gebracht und in `src/main/resources/META-INF/pluginIcon.svg`
   eingebettet (`width="40" height="40"`, Bild bei 2/2 mit 36×36, also 2 px transparenter Rand). Das Motiv
   funktioniert auf hellem und dunklem Hintergrund, daher gibt es kein `pluginIcon_dark.svg`.
   JetBrains empfiehlt eigentlich echte Vektoren unter 3 kB; ein eingebettetes Bitmap wird aber dargestellt
   (geprüft mit IntelliJs `IconLoader`). Nachbauen (Pfade anpassen):
   ```bash
   magick crop.png -background none -gravity center -extent 1584x1584 -filter Lanczos -resize 144x144 -strip icon.png
   pngquant --force --speed 1 128 --output icon-q.png icon.png && oxipng -o max --strip all icon-q.png
   ```
   `dev/design/logo.svg` (Pixel-Wurm) ist der frühere Entwurf. Entwürfe und Rohbilder liegen lokal in `dev/`
   (git-ignoriert).
4. **Screenshots** für die Marketplace-Seite (empfohlen 1280×800, keine winzige Schrift, keine Werbung):
   z. B. Popup mit Suchbegriff + Hervorhebung, Settings-Seite.
5. **JetBrains-Account** auf https://plugins.jetbrains.com: Developer Agreement akzeptieren und im Profil angeben,
   ob du als *Trader* oder *Non-Trader* (EU-Verbraucherrecht) veröffentlichst – privat/kostenlos: Non-Trader.
   Im Marketplace-Profil prüfen, welcher Name öffentlich angezeigt wird, und ihn auf `Peter Denis` setzen – auf der
   Marketplace-Seite steht der Name aus dem Profil, in der IDE der aus `plugin.xml`.
6. **Backup der Signatur-Schlüssel** (z. B. im Passwort-Manager): `~/.jetbrains-signing/repoleap/`
   (`private_encrypted.pem`, `chain.crt`, `password.txt`). Nie committen. Zertifikat gültig bis 09/2036.

## 2. Erstes Release (muss manuell hochgeladen werden)

```bash
./gradlew clean check                 # Tests
./gradlew verifyPlugin                # Plugin Verifier gegen empfohlene IDEs (lädt mehrere GB)
./gradlew patchChangelog              # [Unreleased] -> [1.0.0] - <Datum>
./gradlew buildPlugin signPlugin verifyPluginSignature
```

1. `build/distributions/repoleap-1.0.0-signed.zip` auf https://plugins.jetbrains.com/plugin/add hochladen
   - License: `https://github.com/ryoga86/repoleap/blob/main/LICENSE`
   - Tags z. B. *Navigation*, *VCS*, *Productivity*
2. Änderung an `CHANGELOG.md` committen, taggen, pushen:
   ```bash
   git commit -am "Release 1.0.0" && git tag 1.0.0 && git push && git push --tags
   ```
3. Review durch JetBrains dauert ca. 1–4 Werktage. Danach auf der Plugin-Seite ergänzen: Source Code
   (`https://github.com/ryoga86/repoleap`), Issue Tracker (`…/issues`), Screenshots.
4. **Spendenlink**: im Admin-Bereich des Plugins unter *Monetization* eintragen (Link `https://ko-fi.com/ryoga86`,
   Titel z. B. „Ko-fi“). Nicht in die Beschreibung – das verbieten die Marketplace-Regeln
   („Donation links should not be included within the plugin description“). Im GitHub-README ist er erlaubt.

> Nach dem Push legt `build.yml` ein Draft-Release `1.0.0` an – das **nicht** veröffentlichen (Version ist schon im
> Marketplace). Draft löschen oder direkt `pluginVersion` auf die nächste Version setzen.

## 3. Folge-Releases über GitHub Actions

Einmalig die Repository-Secrets anlegen (*Settings | Secrets and variables | Actions*):

| Secret                 | Inhalt                                                                      |
|------------------------|-----------------------------------------------------------------------------|
| `PUBLISH_TOKEN`        | Marketplace-Token (https://plugins.jetbrains.com/author/me/tokens)          |
| `CERTIFICATE_CHAIN`    | Inhalt von `~/.jetbrains-signing/repoleap/chain.crt`                         |
| `PRIVATE_KEY`          | Inhalt von `~/.jetbrains-signing/repoleap/private_encrypted.pem`             |
| `PRIVATE_KEY_PASSWORD` | Inhalt von `~/.jetbrains-signing/repoleap/password.txt`                      |

Ablauf pro Release:

1. Änderungen unter `## [Unreleased]` in `CHANGELOG.md` eintragen, `pluginVersion` in `gradle.properties` erhöhen
   (`1.1.0`; Pre-Releases wie `1.1.0-beta.1` landen automatisch im Channel `beta`).
2. Auf `main` pushen → `build.yml` baut, testet, verifiziert und legt ein **Draft-Release** an.
3. Draft auf GitHub prüfen und veröffentlichen → `release.yml` patcht den Changelog, signiert, lädt in den
   Marketplace hoch, hängt die ZIPs ans Release und öffnet einen PR mit dem aktualisierten Changelog → mergen.

Alternativ lokal: Token in `~/.gradle/gradle.properties` als `repoleap.publishToken=perm:…` eintragen und
`./gradlew publishPlugin` ausführen.

## Hinweise

- **Eigener Shortcut ⌥⌘P**: *Settings | Keymap* → „Open Repository“ → *Add Keyboard Shortcut* → ⌥⌘P → bei der
  Konfliktmeldung *Remove* wählen (entfernt ihn von *Introduce Parameter* und vom installierten Plugin
  *Easy Open Project*).
- **Alte Testversion** „Repo Switcher“ (`dev.pdenis.reposwitcher`) vor der Installation von RepoLeap deinstallieren –
  andere ID, beide wären sonst parallel aktiv. Die Root-Ordner müssen einmal neu eingetragen werden.
- Plugin-ID, Action-ID (`de.pdenis.repoleap.OpenRepository`) und `@State(name = "RepoLeapSettings")` nach dem
  Release nicht mehr ändern – sonst verlieren Nutzer Einstellungen bzw. eigene Shortcuts.
