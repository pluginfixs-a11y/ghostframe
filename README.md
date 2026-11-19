# Ghost Frame

Server-side Fabric mod for Minecraft **26.2**. Phantom membrane becomes a decoration tool:

* right-click an item frame (normal or glow) with a membrane → the frame turns invisible, the item inside
  stays where it was and can still be inserted, swapped and rotated;
* **Shift**+right-click a sign (standing or wall) with a membrane → the board and post turn into **floating
  text** of the same colour, size and line breaks; punch the spot and the sign comes back with its text.

Vanilla clients need nothing installed.

## Item frames

`ItemFrame` is an entity with the shared `Invisible` flag, and vanilla already renders it that way:
`ItemFrameRenderer.extractRenderState` clears the frame model when the entity is invisible and moves the
framed item from `z = 0.4375` to `z = 0.5`, i.e. flush with the wall. The mod only flips that flag on the
server — the client gets it through `SynchedEntityData`, exactly like a `/summon item_frame ~ ~ ~ {Invisible:1b}`
frame.

| click | result |
|---|---|
| right-click frame with phantom membrane | frame toggles invisible / visible, 1 membrane consumed (creative: free) |
| right-click frame empty-handed or with any other item | vanilla: put the item in, or rotate the framed item |
| Shift + right-click frame with phantom membrane | vanilla: the membrane goes **into** the frame as a displayed item |
| hitbox of an invisible frame | unchanged — items can be put in and rotated as usual |
| break an invisible frame | drops a normal item frame + its item, the membrane is not returned |

## Signs

A sign cannot be made invisible: `AbstractSignRenderer` draws the board model unconditionally, and `SignText`
has no visibility flag at all (`messages`, `filtered_messages`, `color`, `has_glowing_text`). A `block_display`
is no help either — it shows a *block model*, and sign text lives in a block entity, not in a model.

So the mod swaps the 26.2 display entity instead:

| click | result |
|---|---|
| **Shift** + right-click a **wall** sign with phantom membrane | the block is replaced by an invisible marker and the text is painted **on the wall the sign was nailed to**, like graffiti: flush, not floating in the sign's cell |
| **Shift** + right-click a **standing** sign with phantom membrane | the block is replaced by an invisible marker, the text stays hanging where the board was |
| plain right-click a sign | vanilla: the sign editor opens (this is why hiding needs Shift) |
| Shift + right-click a **completely empty** sign | nothing: a marker without any text would be an invisible occupied cell with no hint where it is |
| Shift + right-click a wall sign whose text is only on the **back** | nothing either — that side faces the wall, so the graffiti would be blank |
| punch the marker | the sign comes back in place with its own NBT: all four lines, dye colour, glowing ink, waxed flag, **both sides** |
| right-click the marker with phantom membrane | same as punching it: the sign comes back, the text display goes away |
| right-click the marker with a block in hand | nothing happens (the marker does not get replaced), so the text cannot be orphaned |
| place a block against a wall **next to** the marker | blocked while holding a block, bucket or flint and steel: `structure_void` is replaceable, so vanilla would put it into the marker's cell |
| right-click a chest / furnace / button next to the marker with a block in hand | vanilla: the container opens (the mod only blocks placement, and vanilla gives containers `TRY_WITH_EMPTY_HAND`) |

The marker is `minecraft:structure_void`: invisible, no collision, instantly breakable, and vanilla knows it,
so a vanilla client renders exactly the same world. Its 6×6×6 px shape in the centre of the cell is what you
aim at — the only trace on screen.

### Text geometry

Copied from the vanilla renderers, not eyeballed:

* `StandingSignRenderer.textTransformation` builds
  `T(0.5,0.5,0.5) · R(−angle) · T(wall) · [R(180°) for the back] · T(TEXT_OFFSET) · S(1/96, −1/96, 1/96)`,
  where `TEXT_OFFSET = (0, 0.33333, 0.046667)` and `wall = (0, −0.3125, −0.4375)` for wall signs. The 180°
  rotation of the back side turns *before* `TEXT_OFFSET`, so the back text is mirrored in z — that is the
  detail that decides whether both sides read properly.
* `DisplayRenderer.TextDisplayRenderer.submitInner` applies `R(180°) · S(−0.025, −0.025, −0.025)`, which is
  the same local frame `S(0.025, −0.025, 0.025)`, so `scale = (1/96) / 0.025 = 0.41667` and the same 10 px
  line spacing. But the display **anchors the text block at its bottom**: the block occupies `y = s·(h − y_px)`
  with `h = lines·10 − 1` above the entity origin, while the sign centres its 4-line block on the transformation
  origin. Hence the extra `(20 − 39)/96 ≈ −0.198` shift of the entity — without it the text floats a fifth of
  a block too high.

Result: `billboard: fixed`, yaw = sign angle (`direction.toYRot()` for wall signs,
`RotationSegment.convertToDegrees` for standing ones; the back side is `+180°`), `background: 0` (no plate),
`line_width: 1000` (no auto-wrap, see below). Colours follow vanilla: dye colour at 40 % brightness for normal
ink (`AbstractSignRenderer.getDarkColor`), full colour for glowing ink, and glowing ink additionally gets
`brightness: {block: 15, sky: 15}` — the display's own light override, which is what makes vanilla's glow ink
readable in the dark. Without it the graffiti would simply be black in an unlit room.

**Wall signs are graffiti, standing signs hang.** A wall sign's text belongs on the board (0.109 from the wall
plane), and with the board gone that text visibly floats in the air. So a wall sign paints its text on the wall
itself: the same height, but `0.012` from the block face — a centimetre in front of the texture, enough to never
z-fight with it. Its back side is not rendered at all: vanilla draws it inside the board, and here it would land
on the same plane as the front. The data of both sides still goes into the display's tags, so punching the
marker brings the whole sign back. Standing signs keep the vanilla placement: two displays, front and back,
where the board used to be.

### Known differences from a real sign

* glowing ink has no true outline in a text display — it is the dye colour without the dark halo, but it is
  fully lit (`brightness`) exactly like the real thing;
* a line longer than 90 px is truncated by a sign but **overflows to the sides** here. Wrapping was rejected on
  purpose: the display anchors text at the bottom, so one wrapped line would push all four lines up by 10 px;
* a wall sign's back side is not shown (it faces the wall; vanilla hides it inside the board too), and a wall
  sign with text only on the back is not hidden at all — otherwise the marker would be an empty invisible cell;
* click commands / score values inside sign text (`ClickEvent`, `Score`) are drawn but not clickable;
* server-side text filtering (`filtered_messages`) does not apply: a `text_display` has one text for every
  viewer, so a filtered sign shows its raw text while hidden — `getMessages(false)` is used on purpose;
* **hanging signs** (ceiling / wall hanging) are not supported: different renderer and offsets, the click falls
  through to vanilla.

The sign's own data is base64-encoded into the display's entity `Tags` (`ghostframe:data:…` plus
`ghostframe:anchor:…`), so it survives a restart without any `world/data` file and without a custom block —
a custom block would be impossible anyway: a server-only mod cannot put its own block into a vanilla client's
chunk data.

## Sound and cost

`entity.phantom.flap` when a frame or sign disappears, `entity.phantom.swoop` when it comes back. One
membrane per hide (creative: free); restoring is free, because nothing is created — the sign simply returns.

## What it does not touch

* frame item, rotation, map binding, comparator output — untouched;
* `GlowItemFrame` — the same entity flag, so an invisible glow frame keeps everything glowing;
* hitbox and collision — `setInvisible` does not recalculate the bounding box, so the frame stays clickable
  and items do not float away from the wall;
* frames with `Fixed:1b` (map walls in adventure maps) — they are toggled too, the flag is entity-wide; vanilla
  just refuses the plain click on them;
* reach — the mod repeats the vanilla checks by hand (`isWithinEntityInteractionRange(frame, 3.0)`,
  `isWithinBlockInteractionRange(pos, 1.0)`), because cancelling the Fabric event would otherwise skip the
  packet handler's own distance test.

Known limits: an explosion, `/setblock`, falling sand/gravel, flowing water or a piston can destroy the marker, leaving
the text without a way back — clean it with `/kill @e[type=text_display,tag=ghostframe:sign]`. The marker cell is occupied:
no other block can be put into the cell while the text is hidden. A waterlogged sign loses its water when hidden.
Punching the marker also cancels the client's predicted break, so holding the attack button through the marker can
make the client target the block behind it — tap instead of holding. A ghost hidden by 1.2.0 keeps the old
placement (text 11 cm off the wall) until its sign is restored - restore it and hide it again to get the graffiti. If another mod or a region protection refuses
to place the sign back, the marker stays and its break is cancelled as well: the text data is never thrown away
just because one attempt failed.

## Client side

`environment: "server"`. A vanilla client hides the frame itself (vanilla rendering logic) and renders
`text_display` / `structure_void` as the vanilla entities and blocks they are. The client predicts the vanilla
insert on the frame click, so the item may blink for one tick before the server's `DATA_ITEM` update takes it
away. No desync.

Single-player does **not** load this mod: Fabric Loader never loads `environment: "server"` mods in a client.
Change that line to `"*"` and rebuild if you need it in a solo building world.

## Self-test

`-Dghostframe.selftest=1` (or env `GHOSTFRAME_SELFTEST=1`) builds walls, five frames and six signs in the spawn
chunk, drives the real handlers plus vanilla `ItemFrame#interact` and `SignBlockEntity` through a `FakePlayer`,
and logs 21 checks:

```
SELFTEST hide-empty:          PASS result=Success[…] invisible=true мембран=2
SELFTEST item-into-hidden:    PASS предмет=1 minecraft:diamond invisible=true в руке пусто=true
SELFTEST rotate-in-hidden:    PASS поворот 0->1 предмет=1 minecraft:diamond invisible=true
SELFTEST show-again:          PASS invisible=false предмет=1 minecraft:diamond мембран=1
SELFTEST sneak-keeps-vanilla: PASS result=Pass[] invisible=false мембран=2, предмет=1 minecraft:phantom_membrane
SELFTEST creative-no-consume: PASS invisible=true мембран=1
SELFTEST glow-frame:          PASS invisible=true мембран=1
SELFTEST out-of-reach:        PASS result=Pass[] invisible=false мембран=2
SELFTEST sign-geometry:       PASS стена=E+…012 S+…012 W+…012 N+…012 земля=(…0.5, …0.6354, …0.5467)/(…0.4533) текст=Привет\nМир\n\n цвет=4c6600/bfff00
SELFTEST sign-needs-shift:    PASS result=Pass[] блок=oak_wall_sign мембран=3 дисплеев=0
SELFTEST sign-hide:           PASS result=Success[…] маркер=true мембран=2 дисплеев=1
SELFTEST sign-display-nbt:    PASS background=0 billboard=fixed line_width=1000 текст=true масштаб=true яркость=true
SELFTEST sign-display-sides:  PASS граффити у стены=true в точке (…0.012, …0.3229, …0.5)
SELFTEST sign-restore:        PASS ломать разрешено=false блок=oak_wall_sign текст/цвет/воск=true дисплеев=0
SELFTEST sign-anchor-guard:   PASS клик блоком: маркер=true камень=5 | соседняя стена: маркер=true камень=4 | соседняя стена палкой: Pass[] | ПКМ мембраной: oak_wall_sign, 0 | чужой void ломается=true
SELFTEST standing-sign:       PASS дисплеев=2 позиция=(…0.4533, …0.6354, …0.5) и обратная 0.5467 yaw 90/270 без яркости=true
SELFTEST sign-empty-refused:  PASS result=Pass[] блок=oak_wall_sign мембран=2 дисплеев=0
SELFTEST sign-back-only-refused: PASS result=Pass[] блок=oak_wall_sign мембран=2 дисплеев=0
SELFTEST standing-back-only:  PASS result=Success[…] дисплеев=1 ломать=false возврат=true
SELFTEST old-ghost-restore:   PASS клонирован=true до удара дисплеев=2 ломать=false возврат=true дисплеев=0
SELFTEST sign-neighbour-chest: PASS маркер=true | клик камнем по сундуку: Pass[], маркер=true | Shift+камень: Success[…]
SELFTEST ИТОГ: 0 провалов
```

The world `run/selftest` survives runs, so the test wipes its own cell grid and entities first, and starts 20
ticks after boot: a `text_display` created in a chunk whose entity section is still `HIDDEN` is invisible to
`getEntitiesOfClass`, and every display check would fail for the wrong reason.

Run in the dev server:

```
$env:GHOSTFRAME_SELFTEST='1'; .\gradlew.bat runServer
```

`run/server.properties` (port 25566, flat world) and `run/eula.txt` are committed so the test does not collide
with `D:\serv 26.2`.

## Install

Fabric Loader ≥ 0.20.1 + Fabric API on the server, jar into `mods/`.

## Build

```
./gradlew build
```

## License

MIT.
