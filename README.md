# {C} Char's Far View

See terrain far past your render distance on a weak laptop. Forge 1.20.1, PC side only: you can join any server with it, and nobody else needs it.

## How it works
- The world is kept as **tiles**: 64 x 64 columns each. Level 0 uses 2 blocks per column, each level up doubles that (2, 4, 8 ... 128 blocks per column).
  Each column is one height, one top colour and one side colour.
- **Where the data comes from, best first:**
  1. Chunks your game has loaded, read off-thread when they load and unload.
  2. On your own world (singleplayer or hosting): the `.mca` region files, read straight from disk by its own reader, nearest first, without the game loading a thing. A region file that changes later (a plugin, WorldEdit, an old save put back) gets read again.
  3. On a server with this mod that has sharing switched on: chunk surfaces read from its region files and sent over a slow, rate limited channel (off unless the server's owner turns it on). That includes anything a plugin generated, because it is what is really in the files.
  4. On your own new world: **fast generate from seed**. For anything not real yet, it asks the world's own terrain maths for the surface one point at a time, makes no chunks and touches nothing. Biome from the climate, ground from the biome, trees as seeded bumps counted from the biome's own feature list. These columns are flagged as estimates: anything real replaces them, dissolving in.
- **Truth check:** real chunks are compared with what the seed says as they come in. If more than 30% of 50 or more chunks are off by over 8 blocks (terrain plugins, big edits, odd generators), the seed is declared wrong for that world: everything it made is thrown away and that verdict is saved with the cache. Worlds that aren't plain noise generated worlds, and anywhere plugins can run, skip the seed from the start. A server's seed is never used.
- **Colours** are the average of the real block textures, layered the way the block's model is, tinted through the game's own colour rules, so modded blocks and resource packs just work.
- **Drawing:** greedy merged quads, 8 bytes a vertex, one static buffer per tile, right after the sky with its own far-reaching view. Then the depth buffer is wiped and the real chunks paint over it.
  Far terrain cuts itself out where real chunks are drawn (a tiny mask texture, one pixel per chunk). A tile that changes dissolves into its new look.
- **Fog:** the game's fog is pushed out to the far edge, so real chunks don't fade away near you, and both fade into the same sky colour far away. Where there's nothing past the real chunks yet, the game's own fog stays.

Data is kept in `<game folder>/charlod/<world>/<dimension>/` as about one file per 16 x 16 tiles per level. Each singleplayer world has its own cache by folder name and seed.

## Keys and commands
- F9: on / off
- F10: settings (also through the Mods list)
- `/charlod rebuild`: throw away this world's far terrain and start it again (same as the button in the settings)
- `/charlod status`: the numbers

## Settings (`config/charlod.properties`)
`enabled`, `distance` (blocks), `quality` (LOW, BALANCED, HIGH), `threads` (low priority workers), `useGeneratedChunks`, `seedGenerate`, `askServer`, `showHud`, `memoryMb` (tiles kept in memory), `vramMb` (graphics memory limit; it draws coarser rather than go over).

## Server (optional)
Put the jar in the server's mods folder and set `shareChunkSurfaces=true` in `config/charlod-server.properties`. Only surfaces are ever sent, never anything underground, and never the seed.
`maxKilobytesPerSecond` (default 96) and `maxDistance` set the limits.

## Building and testing
- `gradlew build` makes the jar in `build/libs`.
- `gradlew test` runs the pure logic tests (`core`: tiles, merging, greedy mesh, `.mca` reader on generated files, disk format, tile choice, seed rules, truth check).
- `gradlew build -Psmoke -x test`, then `python tools/run_real_client.py` runs the real game in a throwaway folder (`run-real`): `survey` prints biome maps, `pregen` makes chunks, `scenes` walks through `run-real/scenes.txt` taking screenshots and measuring frame rates.

MIT licence, see `LICENSE`.
