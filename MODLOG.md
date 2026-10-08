# BackroomsCraft build journal

## Idea (agreed 2026-10-08)
Escape the Backrooms' real Level 0, played as Minecraft's Steve. Solo. You start in Level 0 and look for the exit while an
entity hunts you. Everything around Steve is Escape the Backrooms (map geometry, textures, sounds, the entity, flashlight,
almond water), read from the player's own Steam copy; Steve, the HUD, inventory, hunger, combat and placing blocks are
Minecraft. No cubes for the environment: the real meshes are drawn. Host = Minecraft Java (Fabric); Escape the Backrooms is
never launched or changed (Melty role "secondary": it is not in Melty's catalog, so it cannot be the host).

First version must have: the whole real Level 0, one real entity chasing, real flashlight and almond water, sanity,
Minecraft survival, an exit that ends the run.

Reference for repo layout and docs: https://github.com/siddoff/Minecraft-Ring. Template for the build, sheets, autotest
and Melty packaging: the sibling project `../Bodycam + Minecraft` (BodycamCraft, live on Melty).

## Recon (2026-10-08)
- Escape the Backrooms: Steam app 1943950, `steamapps/common/EscapeTheBackrooms`, build 24997718. Unreal Engine 4.27.
  One legacy pak v11 (27 GB), index not encrypted, Zlib blocks (Java's Inflater reads it: no native decoder needed).
  No anti-cheat files in the install.
- 74,945 files, 236 maps. Level 0 = `/Game/Maps/Level0` + streamed sublevels `/Game/Maps/Level0/Pitfalls` (always loaded)
  and `/Game/Maps/Level0/ManillaRoom` (dynamic).
- Level0.umap: 8,386 mesh instances of 59 meshes, 887 lights (872 ceiling spot lights, intensity 125, outer cone 120),
  19 PlayerStarts tagged Start / Exit / Manilla / Hub / CaveExit. It is a fixed map, not generated at run time.
  Bounds in cm: x -11967..11909, y -8050..20285, z -1560..434. Floor at z=0, ceiling at z=400, PlayerStart z=92.
- Blueprint actors keep their component defaults in the blueprint package (`<Name>_GEN_VARIABLE` templates); the level only
  stores what differs. (H)ISM components store per-instance 4x4 matrices after their properties.
- Meshes: cooked StaticMesh, LOD0 inline in the .uexp (position buffer, then tangents + half-float UVs, 16-bit indices).
  `SM_Wall_4m` is one quad, 400 x 400 cm.
- Materials: MaterialInstanceConstant of `/Game/Backrooms/Materials/M_Master`: texture parameter `BaseColor`, scalar
  `Tiling`, vector `BaseColorMultiplier` (the wallpaper's yellow is this multiplier), `EmissiveMultiplier` for the lamps.
- Textures: Texture2D, PF_DXT1 (and others), mip 0 dropped, mips >= 256 px in the .ubulk, smaller ones inline.
- Sounds: SoundWave with inline Ogg Vorbis (Minecraft plays Ogg itself).
- Gameplay actors in Level 0: `Bacteria_BP_C` (mesh `/Game/Entity/Bacteria/creature_unwrap`, anims
  `/Game/Entity/Howler/Zombie_Run_Anim`, `Attack_Anim`, sounds `/Game/Sounds/Bacteria/Chase_Cue`,
  `/Game/Sounds/Jumpscares/BacteriaJumpscare`), `BP_DroppedItem_Flashlight_C`
  (`/Game/FPS_Light_Pack/Meshes/Flashlight/SM_Flashlight`), almond water (`/Game/Items/AlmondWater/AWC_low`, four in
  ManillaRoom), exit `BP_Level0Exit_C` at (-9083, 3010, 54), ambience `/Game/Sounds/Level0/level0_loop`.

## Route
Fabric mod on Minecraft 1.21.11 (same toolchain as BodycamCraft: Mojang mappings, Loom 1.18, Gradle on JDK 25).
The mod finds the Steam copy, reads the pak in Java, bakes Level 0 once into a cache in the instance folder
(render meshes with baked vertex light, 12.5 cm collision voxels) and draws it with its own render pipeline in a void
dimension. Collision is an invisible block whose shape comes from the voxels, so Minecraft physics, mobs and block placing
work on the real geometry.

Coordinates: Minecraft (x, y, z) = Unreal (x, z, y) / 100, plus 64 on y. 1 block = 1 m.

## Tools (reused from `../Bodycam + Minecraft/tools`, nothing new installed)
- JDK 25 (Gradle), JDK 21, Python venv with numpy and Pillow, decompiled Minecraft 1.21.11 and Fabric API sources,
  universal-modder.

## State on 2026-10-08
- Working title and mod id: BackroomsCraft / `backroomscraft` (group `gg.backroomscraft`). Not yet agreed as the listing title.
- Build: `cd mod`, `JAVA_HOME` = `../Bodycam + Minecraft/tools/jdk-25.0.4.1+1`, `./gradlew build`. Python for the sheets:
  `../Bodycam + Minecraft/tools/pyenv/Scripts/python.exe design/sheets.py gen`.
- Outside the game: `./gradlew dump -PdumpArgs="<folder>"` bakes Level 0 in about 3 s and writes a floor plan PNG with the
  baked light, the first textures, and what it could not use.
- In the game: `./gradlew runClient -Pautotest` -> one `AUTOTEST PASS/FAIL` line in the output and screenshots in
  `mod/run/screenshots`. PASS on 2026-10-08: player put at a real start, standing on the real floor (y 64.00), walked
  12.95 blocks, 118 fps with 1.96 M triangles in 96 batches and 81 textures.
- Done: pak/package/mesh/texture/material/level/sound readers (`etb/`), bake (`bake/`: merged meshes split to 1 m edges,
  vertex light from the map's lamps with voxel shadows plus a bounce term, 12.5 cm collision voxels, cache in
  `<instance>/backroomscraft/cache/level0.bin`), dimension `backroomscraft:level0` with its own chunk generator, the
  invisible collision block, drawing with the mod's own pipeline (`client/LevelDraw`), runs (start, exit, fall).
- Not done: entity, items (flashlight, almond water, kit, map pickups), sanity + HUD, sounds, README, package.py,
  melty.json, publishing. No commits yet.
- Gotchas:
  - Light intensities: the ceiling spots are 125 in lumens; `LevelReader` converts units, `light_scale` in the render sheet
    is tuned by eye (the dump prints floor light percentiles; median about 0.75 is right).
  - Ceilings get no direct light from lamps mounted on them: the bounce term in `LightBake` is what lights them.
  - Triangles are re-ordered counter-clockwise against their own vertex normals after the axis swap, so mirrored
    instances and both handedness conventions come out right.
  - GPU textures and samplers cannot be created inside a render pass: `LevelDraw` resolves them before opening its pass.
  - A fresh run folder opens on the accessibility onboarding screen; the autotest skips it.
  - Changing anything in the bake needs `bake_version` raised in the render sheet, or players keep the old cache.
  - The session's GateGuard hook refuses the first Write of every new file: state the facts, then write again.

## State on 2026-10-08, later
- Version 0.1.0 is feature complete for the agreed scope and packaged: `dist/backroomscraft-0.1.0.jar` (218 KB, no game
  files) and `dist/BackroomsCraft-Minecraft-0.1.0.zip` (Prism bundle). `melty.json` validated by Melty's
  `validate_recipe` (52 files placed, 0 left out) and `one_click_check` says yes / publishable.
- AUTOTEST PASS from each of the four start points and with the full timeline (kit, flashlight, almond water,
  idle entity moved onto the trail, exit, chase and hit). Sheets preflight CLEAN; 16 rows still "pending" (dark-sanity
  rules, heartbeat, the messages for a missing game, tooltips, respawn hook): listed by `design/sheets.py`.
- Added since the earlier entry: skeletal mesh / animation readers and `Skinning`; `BacteriaEntity` with its own chase
  goal; `CreatureDraw` (CPU skinning, drawn in the level's pass); items with `EtbItemRenderer`; `Sanity` (Fabric data
  attachment); `EtbSounds` (the game's Ogg data streamed by Minecraft); `ClientGame`; `SanityHud`; distance and
  behind-camera culling per 32-block square in `LevelDraw`.
- Not done: the Melty draft (title, tagline, description, license and remix choice need the owner's answer), uploads,
  the listing screenshot upload, publishing, the GitHub repository and the first commit. The packaged jar has only been
  run from Gradle's dev client, not yet from the Prism bundle (that needs a Microsoft sign-in).
- Melty calls: `%TEMP%/etb-recon/melty/call.py <tool> <args.json>` with the token in the MELTY_TOKEN environment
  variable only; `build_args.py <project root>` writes the argument files from dist/.
- Gotchas found on the way:
  - The Bacteria's animations use Unreal's per-track codec with Float96NoW and Fixed48NoW keys only, key count 1 or the
    frame count; `AnimReader` refuses anything else. Track and scale offsets are the first ints of the byte stream.
  - Its material is metal: the base colour texture shown as plain colour is far too light. The entities sheet's
    `shade` darkens it.
  - In the map the Bacteria stands in a closed room (the dump's flood fill cannot reach it). `Run.relocate` moves an
    idle, far entity onto the player's trail; that is what lets it out.
  - Mob path-finding: cells with geometry above half height are BLOCKED through Fabric's LandPathNodeTypesRegistry.
    Vanilla MeleeAttackGoal does nothing without a path, so the entity has its own goal that also runs straight at a
    target in plain sight.
  - A hitbox 1.0 wide and 3.4 high could not path from one of the starts; 0.7 x 2.9 can from all four.
  - Item meshes are not modelled at one scale (the can is huge): the items sheet's `hand_size` sets their held size.
  - The Bash tool fails on heredocs with an odd number of apostrophes in the text: patch with double quotes only.

## Melty draft (2026-10-08)
- Listing title agreed: "Steve in the Backrooms" (mod id and file names stay `backroomscraft` / `BackroomsCraft`).
  License MIT, remixes off, author issac_leral. GitHub: the owner chose "Melty first, GitHub later"; no repository and
  no commits yet.
- modId `f35d7a31-c7b5-4a4d-b046-cd4749346d3b`, slug `steve-in-the-backrooms`,
  Studio https://melty.gg/studio/f35d7a31-c7b5-4a4d-b046-cd4749346d3b, page https://melty.gg/m/steve-in-the-backrooms.
- Release 0.1.0 submitted as a draft: both files uploaded, recipe = `melty.json`, one click yes, five screenshots added
  (cover: the Bacteria in first person). Not published: waiting for the owner's go-ahead, then `publish`, and it goes
  live once they press Play on its page in the Melty app.

## Rules
- Never commit or ship Escape the Backrooms files; extracted data stays in the temp folder or the instance's cache.
- Sheets in design/sheets are the source of truth: change the sheet, run `python design/sheets.py gen`, then the code.
