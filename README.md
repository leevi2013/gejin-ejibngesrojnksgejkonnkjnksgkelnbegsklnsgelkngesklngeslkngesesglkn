# GambaSlots

Eine virtuelle Erz-Slotmaschine als **Paper-Plugin** für Minecraft Java 1.21.11. Das Chest-Menü ist an den Stil der beigefügten Bilder angelehnt: violetter Rahmen, Münz-Guthaben, Erz-Walzen und eine Gewinnlinie in der mittleren Reihe.

> **Plugin oder Mod?** Dieses Projekt ist ein Server-Plugin für Paper. Es verwendet das normale Minecraft-Chest-GUI und braucht keine Installation auf den Spieler-PCs. Es ist keine Fabric-/NeoForge-Client-Mod und läuft nicht auf einem unveränderten Vanilla-Server.

Alle Münzen sind virtuelle Spielwährung. Es gibt keine Echtgeld-Funktion.

## Funktionen

- `/gamba` öffnet das 6-reihige Chest-Menü.
- Drei Walzen zeigen jeweils drei Erz-Symbole. Die drei Symbole der mittleren Reihe bilden die Gewinnlinie.
- Eine Drehung dauert zufällig 40 bis 80 Ticks, also 2 bis 4 Sekunden.
- Einsatz-Knöpfe: Linksklick ändert den Einsatz um 1 Münze, Rechtsklick um 10.
- `/gamba bet <betrag>` setzt einen Einsatz direkt. Null, negative, nicht numerische oder zu hohe Werte werden abgelehnt und blockieren das Drehen, bis ein gültiger Einsatz gesetzt oder im GUI verändert wurde.
- Ein Einsatz kann im GUI nicht unter 1 Münze sinken. Beim Drehen wird der Einsatz zuerst abgezogen.
- Das Guthaben wird in `plugins/GambaSlots/balances.yml` gespeichert.
- Erz-Gewichte und Gewinn-Multiplikatoren stehen in `plugins/GambaSlots/config.yml` und lassen sich dort ändern.
- `/gamba give <spieler> <betrag>` gibt einem Spieler Münzen (Permission `gamba.admin`, standardmäßig Operator).
- `/gamba reload` lädt die Konfiguration neu (Permission `gamba.admin`).
- GitHub Actions baut das Plugin bei jedem Push und Pull Request.

## Auszahlungen und Zufallsgewichte

Die Auszahlung ist **brutto**, also inklusive des vorher abgezogenen Einsatzes. Bei einem Einsatz von 10 Münzen und drei Diamanterzen (`×8`) werden 80 Münzen gutgeschrieben; der Nettogewinn beträgt 70 Münzen.

| Drei gleiche Symbole in der mittleren Reihe | Multiplikator | Gewicht pro Walze |
| --- | ---: | ---: |
| Kohleerz | ×2 | 65 |
| Eisenerz | ×3 | 15 |
| Golderz | ×5 | 10 |
| Diamanterz | ×8 | 6 |
| Smaragderz | ×15 | 3 |
| Antiker Schrott (Netherit) | ×30 | 1 |

Jede der drei Walzen zieht unabhängig ein Symbol. Die Werte in der Tabelle sind relative Gewichte, keine Prozentangabe für einen Dreier-Treffer.

## Voraussetzungen

- Minecraft Java Edition 1.21.11
- Paper 1.21.11 Server
- Java JDK 21 zum Bauen und Java 21 zum Ausführen des Servers
- Gradle 8.14.3 oder neuer

Laut [Paper-Anforderungen](https://docs.papermc.io/paper/getting-started/) nutzt Paper für Minecraft 1.20 bis 1.21.11 Java 21. Die [Paper-API-Projektanleitung](https://docs.papermc.io/paper/dev/project-setup/) beschreibt die Paper-Abhängigkeit; hier ist sie auf 1.21.11 festgelegt.

## Lokal bauen

1. Installiere ein JDK 21, zum Beispiel [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21), und Gradle 8.14.3 oder neuer.
2. Öffne PowerShell im Projektordner `GambaSlots`.
3. Baue die Plugin-JAR:

   ```powershell
   gradle build
   ```

4. Die fertige Datei liegt hier:

   ```text
   build/libs/GambaSlots-1.0.0.jar
   ```

## Auf einem Paper-Server installieren

1. Lade Paper für Minecraft 1.21.11 von [papermc.io/downloads/paper](https://papermc.io/downloads/paper) herunter und lege die Server-JAR in einen eigenen Serverordner.
2. Starte den Server einmal mit Java 21, zum Beispiel:

   ```powershell
   java -Xms2G -Xmx2G -jar paper-1.21.11.jar --nogui
   ```

   Ersetze `paper-1.21.11.jar` durch den tatsächlichen Dateinamen.
3. Öffne `eula.txt`, ändere `eula=false` zu `eula=true` und speichere die Datei.
4. Starte den Server erneut. Paper legt dabei den Ordner `plugins` an.
5. Kopiere `GambaSlots-1.0.0.jar` aus `build/libs/` nach `plugins/`.
6. Starte Paper neu. Im Server-Log sollte `GambaSlots für Paper 1.21.11 ist gestartet.` erscheinen.
7. Verbinde dich mit einem Minecraft-Client der Version 1.21.11 und schreibe `/gamba`.

Beim ersten Start erhält jeder Spieler standardmäßig 1.000 Münzen. Die Standardwerte und Multiplikatoren können in `plugins/GambaSlots/config.yml` geändert werden. Danach `/gamba reload` ausführen.

## Auf GitHub veröffentlichen

1. Melde dich bei [github.com](https://github.com/) an und erstelle über **New repository** ein neues Repository, zum Beispiel `GambaSlots`.
2. Entpacke diese Projektdateien oder öffne den Ordner `GambaSlots` lokal.
3. Lade **den Inhalt** dieses Ordners in das Repository hoch. `build.gradle`, `settings.gradle`, `README.md` und `src/` müssen direkt im Hauptverzeichnis des Repositories liegen.
4. Sobald du die Dateien hochgeladen hast, startet der Workflow unter **Actions → Build plugin** automatisch.
5. Für eine lokale Git-Veröffentlichung öffne PowerShell im Projektordner und führe aus:

   ```powershell
   git init
   git add .
   git commit -m "Erstelle GambaSlots Paper-Plugin"
   git branch -M main
   git remote add origin https://github.com/DEIN-NAME/GambaSlots.git
   git push -u origin main
   ```

   Ersetze `DEIN-NAME` durch deinen GitHub-Benutzernamen. Falls du beim Erstellen des GitHub-Repositories bereits eine README angelegt hast, lade zuerst den bestehenden Stand herunter oder lege das Repository ohne Initialisierungsdateien an.

## Konfiguration

`src/main/resources/config.yml` ist die Vorlage. Beim ersten Serverstart wird sie als `plugins/GambaSlots/config.yml` angelegt. Pro Erz gibt es:

- `name`: Anzeigename im Menü
- `weight`: relative Häufigkeit bei jedem Walzenzug
- `multiplier`: Brutto-Auszahlung bei drei gleichen Symbolen

Der Einsatz ist immer mindestens 1 Münze. `maximum-bet` kann in der Konfiguration angepasst werden; technisch wird er auf höchstens 1.000.000.000 begrenzt, damit Auszahlungen sicher als ganze Zahlen berechnet werden können.
