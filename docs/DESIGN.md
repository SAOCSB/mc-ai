# MC-AI design

MC-AI is a server-side Fabric mod for Minecraft Java 26.3. Players talk to a large language model in chat; the model replies in chat and, when the player asks for something to be built or done, writes a short JavaScript program. The mod runs that program in a sandbox, which produces a blueprint and a list of world actions, then places the blueprint gradually in front of the player.

Everything runs on the server (singleplayer uses the integrated server), so vanilla clients can join a server that has the mod.

## Modules

| Module | Contents | Builds where |
|---|---|---|
| `core` | Plain Java 21 library with no Minecraft dependency: LLM clients, the Rhino script sandbox, geometry, placement math, config, prompts, the agent loop, i18n. | Anywhere (`./gradlew -PcoreOnly :core:test`) |
| `fabric` | The mod: commands, chat trigger, game-context capture, block validation, tick-based placement, undo, world actions, game tests. | Needs `maven.fabricmc.net` and Mojang's servers; Java 25 |

`fabric` depends on `core` with `implementation project(':core')`, and nests it in the mod jar with `include`. Rhino is nested too. Minecraft already ships Gson at runtime.

## Request flow

1. A player types `/ai <message>` or a chat line that starts with a configured prefix (default `@ai`).
2. `AiService` (server thread) checks permissions, the cooldown and whether the player already has a request running. It then captures a `GameContext` and starts `AiAgent.handle(...)`.
3. `AiAgent` (worker threads):
   - calls the LLM with the system prompt, the trimmed history and the new user turn, streaming progress back as action-bar status;
   - parses the reply into chat text and a script;
   - runs the script in `ScriptEngine`, then validates every block id in the blueprint palette against the game registry;
   - if anything fails, sends the error back to the model and asks for a corrected script, up to `maxRepairAttempts` times.
4. The outcome returns to the server thread:
   - `PlacementPlanner` computes the world transform;
   - `BuildPlanner` turns the blueprint into a `BuildJob`, with block states rotated to the player's facing;
   - `BuildManager` places it a few thousand blocks per tick, recording the previous states for `/ai undo`;
   - when the job finishes, `CommandRunner` executes the world actions, subject to the command whitelist.

## Script API (what the model writes)

Coordinates are local integers; decimals are rounded. **y is up and y = 0 is the ground layer the player stands in.** The structure is viewed from the south: its front faces **+z (south)**, and the player stands south of it looking north (-z). **+x (east) is the player's right.** Block facings use those directions: `facing=south` points toward the player.

Placement (`placement({...})`, default `front`):

| Mode | Behaviour |
|---|---|
| `front` | The bounding box is centred on the player's view line, with the nearest face `distance` blocks in front (default 3). |
| `center` | The bounding box is centred on the player (domes, walls around me). |
| `origin` | Local (0,0,0) is the player's feet. |
| `previous` | Reuses the transform of the player's last build. With `replace: true` the old build is undone first, which is how modifications work. |
| `absolute` | Local coordinates are offsets from world (`x`, `y`, `z`), with no rotation. |

`ground: 'player' | 'surface'` chooses whether local y = 0 is the player's feet level or the terrain surface at the build centre.

| Function | Meaning |
|---|---|
| `block(x,y,z,id)` | One block; `'air'` clears |
| `fill(x1,y1,z1,x2,y2,z2,id)` | Solid cuboid |
| `hollow(...)` / `walls(...)` / `frame(...)` | Cuboid shell / 4 side walls / 12 edges |
| `clear(x1,y1,z1,x2,y2,z2)` | Fill with air |
| `line(x1,y1,z1,x2,y2,z2,id,radius=0)` | 3D line, optionally thick |
| `sphere(cx,cy,cz,r,id,hollow=false)`, `dome(...)` | Sphere / upper half |
| `ellipsoid(cx,cy,cz,rx,ry,rz,id,hollow=false)` | Ellipsoid |
| `cylinder(cx,y,cz,r,h,id,hollow=false)`, `cone(...)` | Vertical cylinder / cone |
| `pyramid(cx,y,cz,half,id,hollow=false)` | Stepped square pyramid |
| `torus(cx,cy,cz,R,r,id)` | Horizontal ring |
| `polygon([[x,z],...],y,h,id,hollow=false)` | Vertical prism from a footprint |
| `text(str,x,y,z,id,scale=1)` | 5x7 pixel letters, readable from the front |
| `replace(x1,y1,z1,x2,y2,z2,from,to)`, `get(x,y,z)` | Edit/read the blueprint built so far |
| `random()`, `randInt(a,b)`, `pick(arr)`, `noise(x,y[,z])` | Deterministic randomness and noise |
| `setName(str)` | Name shown in progress messages |
| `summon(id,x,y,z,nbt?)`, `give(item,count)`, `time(v)`, `weather(v)`, `effect(id,sec,amp)`, `command(str)` | World actions, run after building |
| `log(...)`, `print(...)`, `console.log(...)` | Debug output (shown to the model if the script fails) |

Block ids accept states and NBT like `/setblock`: `oak_stairs[facing=north,half=top]`, `chest[facing=south]{Items:[...]}`.

## Sandbox

Rhino runs in interpreted mode with only the safe standard objects. A ClassShutter denies every Java class. The instruction observer enforces a wall-clock budget and honours thread interruption, and the call depth is capped. The blueprint enforces block-count and span limits. The script is wrapped in a function, so top-level declarations can shadow API names without errors.

## Threading

| Thread | Work |
|---|---|
| Server thread | Commands, chat events, `GameContext` capture, planning, placement, world actions |
| Worker pool (`mcai-worker`) | LLM calls (async HTTP), script execution, block validation (read-only registry access) |

Results come back with `server.execute(...)`.

## Safety defaults

| Concern | Default |
|---|---|
| Block entities (chests, furnaces, signs...) | Never overwritten (`protectBlockEntities`) |
| AI-issued commands | Limited to a whitelist (summon, give, time, weather, effect, particle, playsound, title, tellraw, say, xp, enchant, tp) and `maxPerRequest`. They run as the player at permission level 2. `@a/@e/@r/@n` selectors are rejected unless `allowSelectors` is set |
| Very large builds | Need `/ai confirm` (`confirmAboveBlocks`) |
| Recent builds | Undoable with `/ai undo` (`undoHistory`) |
