---
kind: game
title: "Escape the Backrooms' real Level 0, played as Minecraft's Steve"
game: "Minecraft: Java Edition"
games_also: ["Escape the Backrooms"]
game_version: "Minecraft 1.21.11 with Fabric Loader 0.19.5; Escape the Backrooms Steam build 24997718 (Unreal Engine 4.27)"
platform: windows
engine: minecraft-java
route: loader-api
tools: ["Fabric Loom 1.18", "Fabric API 0.141.6", "Prism Launcher 11.1.1", "Python (recon)", "ffmpeg"]
anti_cheat: "none in either game; Escape the Backrooms is only read, never launched or changed"
status: released
agents: ["Claude Code (Opus 5.5)"]
humans: ["issacleral"]
date: 2026-10-08
links: ["https://github.com/issacleral/Steve-in-the-Backrooms", "https://melty.gg/m/steve-in-the-backrooms"]
tags: ["mashup", "unreal-pak", "level-import", "skeletal-animation", "baked-lighting", "voxel-collision"]
---

# Escape the Backrooms' real Level 0, played as Minecraft's Steve

> A Fabric mod that reads Level 0 out of the player's own Escape the Backrooms install (map, meshes, textures, lamps,
> sounds, the Bacteria with its animations, flashlight, almond water) and plays it inside Minecraft with no blocks standing
> in for the level. It runs in the real game: a scripted in-game test passes from each of the map's four start points.

## Setup

- Windows 11. Minecraft 1.21.11, Mojang mappings, Loom 1.18, Gradle on JDK 25, mod compiled for Java 21.
- Escape the Backrooms, Steam app 1943950: one legacy pak v11 of 27 GB, index not encrypted, Zlib blocks.
- Nothing was installed for the game side: Java's own Inflater reads the pak.

## Route and why

Minecraft is the host and Escape the Backrooms is a content source read in Java at run time. The other way round
(Escape the Backrooms draws, Minecraft is composited in, as Minecraft Ring does with Elden Ring) would have needed a
native bridge inside the Unreal game, and Melty cannot start a game that is not in its catalog. Reading the pak in the mod
keeps it to one jar with no native code and one click to install.

## How the game works (what we had to learn)

- **Pak:** footer of 221 bytes; index with a full directory index; encoded entries; block sizes follow the entry.
- **Packages:** cooked, unversioned UE 4.27 `.uasset`/`.umap` + `.uexp`, tagged properties. Arrays of structs carry one
  inner tag and then the elements.
- **Maps:** components of blueprint actors only store what differs from the blueprint. The rest is in the blueprint
  package as `<Name>_GEN_VARIABLE`, reached through the export's template index. Instanced mesh components keep their
  per-instance 4x4 matrices after their properties.
- **Static meshes:** LOD0 inline: positions, packed tangents, half-float UVs, 16-bit indices. The section record size
  changes between engine versions, so it is derived from where the position buffer starts.
- **Materials:** instances of one master material; `BaseColor` texture, `Tiling`, and `BaseColorMultiplier` (the wallpaper
  is grey; its yellow is this multiplier). Defaults of the base material live in its cached expression data.
- **Textures:** DXT1 and others; mip 0 dropped, large mips in `.ubulk`, small ones inline.
- **Sounds:** SoundWave with inline Ogg Vorbis, which Minecraft plays as it is.
- **Skeletal mesh:** one section, 70,470 vertices, 43 bones; the index buffer ends exactly where the position buffer
  begins; skin weights are four bone bytes and four weight bytes per vertex, through the section's bone map.
- **Animations:** per-track codec. The byte stream starts with the track offsets, then the scale offsets, then the keys.
  Each track has a header (format, which of x y z are stored, key count); only Float96NoW and Fixed48NoW occur.
- **Coordinates:** Minecraft (x, y, z) = Unreal (x, z, y) / 100.

## Build steps

1. `python design/sheets.py gen` (checks the design sheets against the installed game and generates constants).
2. `cd mod && ./gradlew build`.
3. `./gradlew dump -PdumpArgs="<folder>"` to bake the level outside Minecraft and look at pictures of it.
4. `./gradlew runClient -Pautotest` for the in-game check; `-Pautotest=demo` records the captioned tour.
5. `python package.py` for the Melty package.

## Verification

- **Outside the game:** a floor plan drawn from the collision voxels with the baked light; a flood fill showing the exit
  can be walked to from every start; the Bacteria rendered in each animation.
- **In the game:** the autotest makes a world and checks that the player stands on the real floor, walks, has the kit,
  switches the flashlight, drinks, that the idle entity is moved onto the trail, that the exit ends the run, and that the
  entity chases and hits. PASS from all four starts at about 116 fps.
- **Not verified:** sounds by ear, sanity loss in the dark, the message when the game is not installed, respawn, and the
  package run from Prism Launcher rather than from Gradle.

## Gotchas

1. **Light a thousand times too dim.** **Cause:** the lamps are in lumens and the units were converted. **Fix:** one scale
   in the render sheet, tuned until the median floor brightness in the dump was about 0.75.
2. **Black ceilings.** **Cause:** lamps mounted on the ceiling never face it. **Fix:** a bounce term, as if a soft light
   hung two blocks under each lamp.
3. **Inside-out or mirrored meshes.** **Cause:** the axis swap and mirrored instances both flip winding. **Fix:** order
   every triangle against its own vertex normals after transforming.
4. **Crash creating a texture.** **Cause:** GPU resources cannot be created inside a render pass. **Fix:** resolve
   textures and the sampler before opening the pass.
5. **The entity never arrived.** **Cause:** in the map it stands in a closed room that a scripted event opens.
   **Fix:** an idle, far entity is moved onto the trail the player walked.
6. **The entity saw the player and stood still.** **Cause:** Minecraft's melee goal does nothing without a path, and a
   1.0 x 3.4 hitbox had none from one start. **Fix:** its own goal that also runs straight at a target in plain sight, and
   a 0.7 x 2.9 hitbox.
7. **A pale grey Bacteria.** **Cause:** its material is metal, so its base colour is not a surface colour. **Fix:** a
   `shade` value in the entities sheet.
8. **An almond water can that filled the screen.** **Cause:** item meshes are not modelled at one scale. **Fix:** a held
   size per item in the items sheet.

## Assets

None were made. Every mesh, texture, animation and sound is read from the player's copy of Escape the Backrooms.

## Open questions

- Unreal's own baked lightmaps are in the map's built data and were not read.
- The level's puzzles and more levels (236 maps are in the pak).
- Multiplayer: the bake runs per PC, so every player would need the game.
