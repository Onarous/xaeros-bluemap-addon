# AGENTS.md

Fabric-мод для MC 26.1.2 (target Java 21, сборка требует JDK 25): синхронизирует чанки BlueMap в Xaero's World Map.
Пакет `onarous.xaeros_bluemap_addon`, команда `/bmsync`, конфиг `config/xaeros_bluemap_addon.json`.

## Сборка и тест

- Сборка: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"; .\gradlew.bat build --console=plain -x test` → jar в `build/libs/xaerosBluemapAddon-1.0-SNAPSHOT.jar`.
- **ВНИМАНИЕ**: Minecraft 26.1.2 (fabric-loom 1.17.19) падает с Java 21: «Minecraft 26.1.2 requires Java 25 but Gradle is using 21». JAVA_HOME обязан указывать на JDK 25 (иначе configure stage падает). JDK 21 (`jdk-21.0.3.9-hotspot`) для сборки НЕ подходит, хотя `targetJavaVersion = 21`.
- Никаких юнит-тестов/junit нет. Тесты в корне репозитория (`*.java`, `tile.bin`, `rawtile.bin`, `settings.json`) — мусор для отладки парсера, закоммичен только игнор на них.
- Реальная игра для проверки: `F:\CurseForge\minecraft\Instances\vanila 26.1.2\mods\` — копировать туда собранный jar. Xaero там: `xaeroworldmap-fabric-26.1.2-1.44.2.jar`.

## Важные особенности кода

- MC 26.x **не обфусцирован** (`noIntermediateMappings()` + `identity-mappings.tiny`) — в коде напрямую официальные имена Mojang (`Identifier`, `ResourceKey`, `Level`).
- Версии в `gradle.properties`: minecraft 26.1.2, loader 0.19.3, fabric-api 0.155.2+26.1.2.
- **Xaero подключен через официальный Maven** (`https://chocolateminecraft.com/maven/`) как `modCompileOnly "xaero.map:xaeroworldmap-fabric-26.1.2:1.44.2"`.
- Архитектура интеграции:
  - `XaeroMapBridge` — безопасный soft-dependency шлюз: проверяет наличие Xaero (`isModLoaded` / `Class.forName`), управляет потоками и сохраняет fallback-файл при отсутствии мода.
  - `XaeroDirectBridge` — типизированная прямая работа с API и классами Xaero (`WorldMapSession`, `MapProcessor`, `MapRegion`, `MapTileChunk`, `MapTile`, `MapBlock`) без рефлексивного оверхеда.

## Координатная сетка Xaero (проверено по байткоду 1.44.2)

- `MapTile` = 1 чанк; `MapTileChunk` (SIDE_LENGTH=4) = 4×4 чанка; `MapRegion` (SIDE_LENGTH=8) = 8×8 MapTileChunk = 32×32 чанка.
- Индексация: `mtcX = chunkX>>2`; `region = getLeafMapRegion(caveLayer, mtcX>>3, mtcZ>>3, create)`; `tileChunk = region.getChunk(mtcX&7, mtcZ&7)`; `tile = tileChunk.getTile(chunkX&3, chunkZ&3)`.
- Флаг исследования — `MapTile.setLoaded(true)`. Данные блоков — `MapTile.setBlock(lx, lz, MapBlock)`, MapBlock: no-arg ctor + `setState/setHeight/setTopHeight/setLight/setGlowing/setBiome`.

## Рендер-пайплайн Xaero (проверено по байткоду 1.44.2) — почему ручные чанки не рисовались

Проблема была не в раскрытии (`Marked 9025/9025`, 0 ошибок), а в том, что вручную созданные `MapRegion`/`MapTileChunk` не проходили гейты рендер-потока:

- `MapProcessor.onRenderProcess` (render-поток) перебирает `toProcessLevels` и для каждого региона требует `LeveledRegion.shouldBeProcessed()` = `loadState in [1,4)`. Свежий регион имеет `loadState=0` → полностью пропускается.
- Для каждого чанка вызывается `region.getTexture(x,z)` (= `chunk.getLeafTexture()`, создаётся лениво в конструкторе `MapTileChunk` через `createLeafTexture()`), затем `canUpload()` = `tileChunk.getLoadState() >= 2`, и `preUpload()` → `MapTileChunk.updateBuffers(...)` только если `getToUpdateBuffers()`.
- `MapTileChunk.updateBuffers` бросает `RuntimeException` вне клиентского main-потока (`Minecraft.isSameThread()`). `updateBuffers` также вызывается в `MapWriter.writeChunk` (скан у игрока) и `PNGExporter` (recipe: `chunk.setLoadState((byte)2)` + re-register тайлов через `setTile(lx,lz,tile,cache,processor)`).

**Рецепт (реализован в `XaeroDirectBridge.revealOneChunk`)**: после регистрации region/tileChunk/тайлов — `region.setLoadState((byte)2)` + `tileChunk.setLoadState((byte)2)` + **`processor.addToProcess(region)`** (классно: `getLeafMapRegion` кладёт регион только в `regionsListAll`, а рендер-поток `onRenderProcess` перебирает ТОЛЬКО `toProcessLevels[level]`; туда регионы добавляет `MapSaveLoad.addToLoad` → `addToProcess` — вручную это надо вызывать самому; для loadState>=4 после `onProcessingEnd` регион удаляется из очереди и у него уничтожены буферы → нужен `region.restoreBufferUpdateObjects()` + повторный `addToProcess`) + `region.requestRefresh(processor)` (→ `MapProcessor.addToRefresh` → `handleRefresh`, который перерегистрирует тайлы и держит `toUpdateBuffers=true`). Также полезен `region.setHasHadTerrain()`/`tileChunk.setHasHadTerrain()`. Для существующих регионов (loadState 1–3) `addToProcess` не нужен — они уже в очереди.

Замечания: `MapRegion.createTexture(x,z)` **пересоздаёт** tileChunk через `new` и заменяет его в `chunks[][]` (сбросит ручные тайлы) — НЕ использовать для ручных чанков; leafTexture уже создаётся конструктором `MapTileChunk`.

## Координатная сетка BlueMap (.prbm)

- Позиции в `.prbm` **локальные** (0..tileSize внутри тайла). Мировая координата блока: `blockX = translateX + localX + tx*tileSize` (проверено замером на живом сервере).
- `hires.tileSize` и `hires.translate` читаются из `/maps/{id}/settings.json` и переопределяют `cfg.hiresBlockSize` (конфиг — только fallback).
- `tiles/0/` — hires-уровень; `.prbm` может отдаваться как `.prbm.gz` (gzip определяем по magic-байтам `1F 8B`).
- Палитра цветов — динамическая, из всех блоков регистра через `Block.defaultMapColor().col` (MapColor — та же система, что у ванильной карты). Не путать с `net.minecraft.client.color.item.MapColor`.

## Прочее

- git: ветка `main`, origin `https://github.com/Onarous/xaeros-bluemap-addon.git`. Секретов/ключей в репо нет.
- `.gitignore` скрывает `.gradle/`, `build/`, `.idea/`, `*.class`, корневые dev-тесты и дампы.
- Репозитории: Fabric maven + `https://chocolateminecraft.com/maven/` (официальный репозиторий Xaero).
