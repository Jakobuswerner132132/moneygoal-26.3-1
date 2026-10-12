# HugoSMP Money – Minecraft 26.3 (Fabric)

Portierung von `hugosmp-money 1.0.1` (1.21.11) auf **Minecraft 26.3**.
Funktionen unverändert: HUD mit Kontostand, `/moneygoal`, `/moneyinterval`.

## Struktur (muss im Hauptordner des Repos so liegen)

    build.gradle
    gradle.properties
    settings.gradle
    src/main/java/de/hugosmpmoney/HugoSmpMoneyClient.java
    src/main/resources/fabric.mod.json
    .github/workflows/gradle.yml

## Bauen mit GitHub Actions
Dateien ins Repo pushen -> Reiter "Actions" -> Lauf öffnen -> unten bei
"Artifacts" die Datei `hugosmp-money-26.3` herunterladen (ZIP, darin die JAR).

## Bauen lokal
JDK 25 + Gradle 9.5.1 oder neuer installieren, dann:

    gradle build

Ergebnis: `build/libs/hugosmp-money-1.0.1+26.3.jar` (die Datei OHNE `-sources`).

## Installieren
JAR in den `mods`-Ordner, dazu Fabric Loader (0.19.3+) und Fabric API für 26.3.

## Versionen (gradle.properties)
Minecraft 26.3 · Loader 0.19.5 · Loom 1.17.20 · Fabric API 0.162.0+26.3 · Java 25
