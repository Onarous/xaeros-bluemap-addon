# Xaero's World Map ↔ BlueMap Sync Addon

Клиентский Fabric-мод для Minecraft, который раскрывает чанки в **Xaero's World Map**
на основе данных с веб-карты **BlueMap**.

Если на сервере установлен BlueMap, все построенные игроками области уже отрендерены
в виде тайлов. Мод сканирует тайлы BlueMap, восстанавливает рельеф (высоты + тип блока)
и помечает соответствующие чанки как исследованные на карте Xaero.

## Возможности

- Сканирование карты BlueMap через HTTP и разбор бинарных `.prbm`-тайлов
- Параллельная проверка тайлов (настраиваемое число потоков)
- Автоматическое раскрытие чанков в Xaero's World Map (без прямой зависимости — через рефлексию)
- Fallback: если Xaero не установлен или рефлексия не сработала — список чанков сохраняется
  в `xaeros_bluemap_sync.txt` в папке игры
- Конфигурация через файл `config/xaeros_bluemap_addon.json` или через команды

## Требования

- Minecraft **26.1.2**
- Fabric Loader **0.19.3+**
- Fabric API **0.155.2+**
- Java **21**
- (опционально) Xaero's World Map

## Установка

1. Скопируйте `.jar` из `build/libs/` в папку `mods/`.
2. Запустите игру — при первом запуске создастся конфиг
   `config/xaeros_bluemap_addon.json`.
3. Укажите адрес BlueMap и id карты:

   ```
   /bmsync seturl http://your-server:8100
   /bmsync setmap world
   ```

## Команды

| Команда | Описание |
| --- | --- |
| `/bmsync start` | Начать синхронизацию с BlueMap |
| `/bmsync status` | Показать текущий конфиг |
| `/bmsync maps` | Список карт на BlueMap-сервере |
| `/bmsync seturl <url>` | Задать адрес BlueMap |
| `/bmsync setmap <mapId>` | Задать id карты |
| `/bmsync setrange <blocks>` | Радиус сканирования вокруг центра карты |
| `/bmsync reload` | Перечитать конфиг с диска |

## Конфиг

`config/xaeros_bluemap_addon.json`:

| Поле | По умолчанию | Описание |
| --- | --- | --- |
| `bluemapUrl` | `http://localhost:8100` | URL веб-интерфейса BlueMap |
| `mapId` | `world` | id карты (см. `/bmsync maps`) |
| `hiresBlockSize` | `32` | размер hi-res тайла BlueMap в блоках |
| `parallelRequests` | `40` | параллельных HTTP-запросов при сканировании |
| `maxTilesPerSync` | `0` | максимум тайлов за один запуск (`0` = без лимита) |
| `tileRequestTimeoutSeconds` | `6` | таймаут запроса тайла, сек |
| `parseRange` | `300` | радиус сканирования вокруг стартовой позиции карты, блоки |

## Сборка

```
.\gradlew.bat build
```

Готовый мод появится в `build/libs/`.

## Структура проекта

```
src/main/java/onarous/xaeros_bluemap_addon/
├── Xaeros_bluemap_addon.java        # ModInitializer
└── config/
    └── BluemapSyncConfig.java       # конфигурация

src/client/java/onarous/xaeros_bluemap_addon/client/
├── Xaeros_bluemap_addonClient.java  # ClientModInitializer
├── bluemap/
│   ├── BluemapApiClient.java        # HTTP-клиент BlueMap
│   └── PrbmParser.java              # разбор .prbm-тайлов
├── command/
│   └── BluemapSyncCommand.java      # команда /bmsync
└── xaero/
    └── XaeroMapBridge.java          # рефлексивный мост в Xaero
```

## Известные ограничения

- Парсер `.prbm` рассчитан на стандартную структуру тайла (position/normal/color) —
  изменение формата на BlueMap может его сломать.
- Палитра из ~14 блоков аппроксимирует цвет поверхности по ближайшему совпадению.
- Мост в Xaero использует рефлексию и может требовать обновления при смене версии
  Xaero's World Map.

## Лицензия

All Rights Reserved.
