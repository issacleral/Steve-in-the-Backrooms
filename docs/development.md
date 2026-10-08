# Development

You need Escape the Backrooms installed on Steam, a JDK 25 for Gradle (the mod itself targets Java 21) and Python 3.

## Build

```bash
cd mod
./gradlew build
```

The jar is `mod/build/libs/backroomscraft-<version>.jar`.

## The design sheets

Everything the mod is made of is a row in `design/sheets/*.json`: levels, entities, items, sounds, rules, render settings, the kit, text, systems and hooks into Minecraft. Change the sheet first, then:

```bash
python design/sheets.py gen
```

It lists every unfilled cell, every reference that does not resolve and every Escape the Backrooms path that is not in the game's files, then writes `mod/src/main/java/gg/backroomscraft/gen/Sheets.java`, the item models and the language file. Build only when it says `preflight: CLEAN`.

## Check outside Minecraft

```bash
cd mod
./gradlew dump -PdumpArgs="C:/some/empty/folder"
```

Bakes Level 0 from the installed game in a few seconds and writes pictures to look at: a floor plan with the baked light, where a player can walk to from the start points, the textures, and the Bacteria posed with each animation. It also prints what the bake could not use.

## Check in the real game

```bash
cd mod
./gradlew runClient -Pautotest
```

Makes a world, waits to be put in the level, looks around, walks, uses the flashlight and the almond water, waits for the idle entity to be moved onto the trail, reaches the exit, and meets the entity. It prints one `AUTOTEST PASS` or `AUTOTEST FAIL` line and saves screenshots in `mod/run/screenshots`. Add `-Pstart=N` to begin at the map's Nth start point.

## Package for Melty

```bash
python package.py
```

Writes `dist/backroomscraft-<version>.jar` and `dist/BackroomsCraft-Minecraft-<version>.zip` (a portable Prism Launcher with a ready instance). `melty.json` is the install recipe.

## Rules

- Never commit or ship files of Escape the Backrooms. `.gitignore` refuses the usual extensions and `package.py` refuses a jar that contains them.
- Anything that changes the bake needs `bake_version` raised in `design/sheets/render.json`, or players keep their old cache.
