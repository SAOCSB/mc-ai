# Build script API reference

This is the exact contract between the script engine (`core/.../script/ScriptEngine.java`) and the system prompt (`core/src/main/resources/mcai/prompt/system.md`). Both must follow it.

## General rules

- **Coordinates are local integers.** Any number is accepted and rounded with `Math.round`. `NaN`, `Infinity` or a non-number throws `TypeError: <fn>(): <param> must be a finite number`.
- **Axes.** y is up and y = 0 is the ground layer (the layer the player's feet are in). The front of the structure faces +z (south), toward the player. +x (east) is the player's right.
- **Block ids** are strings: `[namespace:]path[state]{nbt}`. They are normalized with `BlockIds.normalize`: `"Stone"` becomes `minecraft:stone` and `"oak_stairs[ facing=north ]"` becomes `minecraft:oak_stairs[facing=north]`. A non-string or malformed id throws `TypeError: <fn>(): invalid block id '<value>'`. Whether a valid-looking id exists in the game is checked after the script finishes. Common mistakes are fixed automatically, for example `stone_brick` → `stone_bricks` and `oak_planks_stairs` → `oak_stairs`, and the player is told. Ids that still don't exist are sent back to the model as a repair request. After the last attempt, positions using unknown ids are dropped instead of failing the whole build.
- **`'air'`** (also `cave_air`, `void_air`) clears the position when built.
- **Overwrites.** A later call overwrites earlier blocks at the same position.
- **Optional booleans** (`hollow`) use JavaScript truthiness and default to `false`.
- **Argument count.** Too few arguments throws `TypeError: <fn>(): expected <n> arguments (<names>), got <k>`. Extra arguments are ignored.
- **Limits.** Exceeding the block budget, the span limit, the action limit or the time limit aborts the script with a clear message.
- **Scope.** The script runs inside a function, so top-level `const`/`let`/`function` names may shadow API names. The return value is ignored.
- **Supported JavaScript.** Rhino 1.9 in ES6 mode, with source rewrites for Rhino's gaps:
  - **Works:** `let`/`const` (the engine rewrites `const` to `let`), arrow functions, template literals, destructuring (including in `for...of`), default and rest parameters, spread in array literals, object spread, spread in calls on named or dotted callees, `for...of`, `Map`/`Set`/`Symbol`/generators, `?.`, `??` and `**`.
  - **Not supported:** `class`, `async`/`await`, rest elements in destructuring, and spread in `new` or on computed callees.
  - **Caveat:** closures created in a `for (let ...)` loop all see the last value.
  - The exact matrix is in `EsFeatureMatrixTest`.

## Blocks

| Signature | Behaviour | Returns |
|---|---|---|
| `block(x, y, z, id)` | One block | undefined |
| `fill(x1, y1, z1, x2, y2, z2, id)` | Solid cuboid; corners inclusive, any order | number of positions written |
| `hollow(x1, y1, z1, x2, y2, z2, id)` | Shell: 4 walls + floor + ceiling, 1 thick. The interior is untouched (call `clear` first for an empty room) | count |
| `walls(x1, y1, z1, x2, y2, z2, id)` | The 4 vertical side walls only | count |
| `frame(x1, y1, z1, x2, y2, z2, id)` | The 12 edges | count |
| `clear(x1, y1, z1, x2, y2, z2)` | `fill` with air | count |
| `line(x1, y1, z1, x2, y2, z2, id, radius = 0)` | Gap-free 3D line, both ends included. `radius > 0` makes a round beam | count |
| `sphere(cx, cy, cz, r, id, hollow = false)` | A block is inside when `dx²+dy²+dz² <= (r+0.5)²` | count |
| `dome(cx, cy, cz, r, id, hollow = false)` | The part of `sphere` with y >= cy | count |
| `ellipsoid(cx, cy, cz, rx, ry, rz, id, hollow = false)` | Axis-aligned ellipsoid | count |
| `cylinder(cx, y, cz, r, height, id, hollow = false)` | Vertical, occupies y .. y+height-1. Hollow = side wall only | count |
| `cone(cx, y, cz, r, height, id, hollow = false)` | Radius r at y, shrinking linearly to a point at the top layer | count |
| `pyramid(cx, y, cz, half, id, hollow = false)` | Layer k (k = 0..half) is the square with \|dx\|,\|dz\| <= half-k. Height = half+1 | count |
| `torus(cx, cy, cz, R, r, id)` | Horizontal ring: major radius R, tube radius r | count |
| `polygon(points, y, height, id, hollow = false)` | Vertical prism over a footprint. `points` is an array of `[x, z]` pairs or `{x, z}` objects (at least 3). Hollow = outline walls only | count |
| `text(str, x, y, z, id, scale = 1)` | 5x7 pixel letters (A-Z, 0-9, basic punctuation) standing in the X/Y plane at depth z, readable from the front. (x, y, z) is the bottom-left corner. Characters advance along +x; `\n` starts a new line below | width in blocks |
| `replace(x1, y1, z1, x2, y2, z2, from, to)` | Within the box, changes blocks already placed by the script whose id equals `from` (normalized) to `to`. `from = '*'` matches any non-air block | count |
| `get(x, y, z)` | The id the script has placed there so far, or `null`. It does not read the world | string or null |

Counts are numbers of positions written, including overwrites.

## Randomness

The seed is fixed per request, so results are reproducible.

| Signature | Returns |
|---|---|
| `random()` | Float in [0, 1). `Math.random` is the same generator |
| `randInt(min, max)` | Integer in [min, max], both inclusive (`min > max` throws) |
| `pick(array)` | A random element (an empty or non-array argument throws) |
| `noise(x, y)` / `noise(x, y, z)` | Smooth gradient noise in [-1, 1]. Scale the inputs, e.g. `noise(x / 16, z / 16)` |

## Placement and naming

`placement(options)` may be called at any time; the last call wins. Option keys:

| Key | Values | Default |
|---|---|---|
| `mode` | `'front'`, `'center'`, `'origin'`, `'previous'`, `'absolute'` | `'front'` |
| `distance` | Gap between the player and the structure in front mode (0..64) | 3 |
| `ground` | `'player'` (feet level) or `'surface'` (terrain at the build centre) | `'player'` |
| `replace` | `true`: undo the player's previous build first. Use it with `mode: 'previous'` to modify a build in place | `false` |
| `x`, `y`, `z` | World anchor for `'absolute'` (missing values default to the player's position) | — |

An unknown mode or ground throws a `TypeError` that lists the valid values. Unknown keys are ignored, with a log line.

`setName(name)` sets the display name used in progress messages (string, at most 64 characters).

## World actions

Actions run in order after the blocks are placed. The server can disable or whitelist them; a rejected action does not fail the script, and the player sees a summary. Each call counts toward the action limit.

| Signature | Effect |
|---|---|
| `summon(entityId, x, y, z, nbt?)` | Spawn an entity at a local block position (block centre). `nbt` is an SNBT string such as `'{CustomName:"Bob",NoAI:1b}'` |
| `give(itemId, count = 1)` | Give items to the requesting player. `itemId` may carry components, e.g. `'diamond_sword[enchantments={sharpness:5}]'`. count 1..6400 |
| `time(value)` | `'day'`, `'noon'`, `'night'`, `'midnight'` or ticks 0..24000 |
| `weather(value)` | `'clear'`, `'rain'` or `'thunder'` |
| `effect(effectId, seconds = 30, amplifier = 0)` | Status effect on the requesting player (seconds 1..1000000, amplifier 0..255) |
| `command(cmd)` | Any whitelisted vanilla command, run as the player at their position (leading `/` optional) |

Invalid argument values (unknown time keyword, count out of range, non-string ids) throw a `TypeError`.

## Debugging

`log(...)`, `print(...)`, `console.log(...)` (also `.info`, `.warn`, `.error`) join their arguments with spaces. Objects and arrays go through `JSON.stringify` when possible. Lines are kept up to the log limit and are shown to the model when the script fails.

## Error messages

Errors are reported as `<ErrorName>: <message> (line <n>: <source line>)`. The line number refers to the original script, and the source line is trimmed to 120 characters. Limit errors are `LimitError: ...` and timeouts are `TimeoutError: script ran longer than <ms> ms`.
