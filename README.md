# {C} Char's Far View

See terrain far past your render distance on a weak laptop. Forge 1.20.1, PC side only: you can join any server with it, and nobody else needs it.

## How it works
- The world is kept as **tiles**: 64 x 64 columns each. Level 0 uses 2 blocks per column, each level up doubles that (2, 4, 8 ... 128 blocks per column).
  Each column is one height, one top colour and one side colour.
- **Where the data comes from:**
  1. Chunks your game has loaded, read off-thread when they load and unload.
  2. On your own world (singleplayer or hosting): the `.mca` region files, read straight from disk by its own reader, nearest first, without the game loading a thing.
  3. On a server with this mod that has sharing switched on: chunk surfaces sent over a slow, rate limited channel (off unless the server's owner turns it on).
- **Colours** are the average of the real block textures, layered the way the block's model is, tinted through the game's own colour rules, so modded blocks and resource packs just work.
- **Drawing:** greedy merged quads, 8 bytes a vertex, one static buffer per tile, right after the sky with its own far-reaching view. Then the depth buffer is wiped and the real chunks paint over it.
  Far terrain cuts itself out where real chunks are drawn (a tiny mask texture, one pixel per chunk).
- **Fog:** the game's fog is pushed out to the far edge, so real chunks don't fade away near you, and both fade into the same sky colour far away.

Data is kept in `<game folder>/charlod/<world>/<dimension>/` as about one file per 16 x 16 tiles per level.

## Keys
- F9: on / off
- F10: settings (also through the Mods list)

## Settings (`config/charlod.properties`)
`enabled`, `distance` (blocks), `quality` (LOW, BALANCED, HIGH), `threads` (1-2 is plenty), `useGeneratedChunks`, `askServer`, `showHud`, `memoryMb` (tiles kept in memory), `vramMb` (graphics memory limit; it draws coarser rather than go over).

## Server (optional)
Put the jar in the server's mods folder and set `shareChunkSurfaces=true` in `config/charlod-server.properties`. Only surfaces are ever sent, never anything underground.
`maxKilobytesPerSecond` (default 96) and `maxDistance` set the limits.

## Building and testing
- `gradlew build` makes the jar in `build/libs`.
- `gradlew test` runs the pure logic tests (`core`: tiles, merging, greedy mesh, `.mca` reader on generated files, disk format, tile choice).
- `gradlew build -Psmoke -x test`, then `python tools/run_real_client.py` runs the real game in a throwaway folder (`run-real`): `survey` prints biome maps, `pregen` makes chunks, `scenes` walks through `run-real/scenes.txt` taking screenshots and measuring frame rates.

MIT licence, see `LICENSE`.
