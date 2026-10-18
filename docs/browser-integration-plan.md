# VibeBridge: План интеграции ТВ-лаунчера с браузером

Архитектура экосистемы для **Xiaomi TV Stick 4K**, объединяющая мобильный/десктопный браузер и ТВ-лаунчер в единую бесшовную среду с единым дизайн-кодом, продолжением видео с таймкодом и мгновенным запуском.

---

## 1. Проблема и цели

Традиционный просмотр видео разделен:
- Пользователь находит фильм или ролик в браузере на ноутбуке или телефоне (YouTube, RuTube, VK Video, Кинопоиск, онлайн-кинотеатры, веб-версия Lampa).
- Чтобы продолжить на ТВ, приходится искать этот же фильм пультом, вбивая буквы в поиск, либо мириться с нестабильным Chromecast, пережимающим качество или требующим включенного экрана.
- Разрозненный интерфейс: веб-страницы выглядят по-своему, стоковый Google TV перегружен рекламой.

**Цели VibeBridge:**
1. **Бесшовный запуск (Web-to-TV Instant Handoff)**: отправка видеопотока, магнет-ссылки или веб-страницы из браузера на ТВ в 1 клик.
2. **Продолжение видео (Cross-Device Resume)**: синхронизация таймкода с точностью до секунды.
3. **Единый дизайн-код (Unified Glass Design)**: одинаковый премиальный минимализм (VisionOS / tvOS Glass) на ТВ и в веб-интерфейсе компаньона.

---

## 2. Архитектура решения

```
┌─────────────────────────────────┐                 ┌─────────────────────────────────┐
│     Десктоп / Смартфон          │                 │     Xiaomi TV Stick 4K          │
│                                 │                 │                                 │
│  ┌───────────────────────────┐  │   Local LAN     │  ┌───────────────────────────┐  │
│  │ Браузер (Chrome / Safari) │  │  (HTTP / WS)    │  │  VibeLauncher (tv-home)   │  │
│  │                           │  │                 │  │                           │  │
│  │ • WebExtension Companion  │──┼────────────────►│  │ • Embedded Nano-Daemon   │  │
│  │   или Web Dashboard (PWA) │  │   port :8989    │  │   (ноль нагрузки в фоне)  │  │
│  │ • HTML5 Video Interceptor │  │                 │  │                           │  │
│  └───────────────────────────┘  │                 │  │ • Watch Next shelf        │  │
└─────────────────────────────────┘                 │  │ • Dynamic App Intent Router│ │
                                                    │  └─────────────┬─────────────┘  │
                                                    │                │ Android Intent │
                                                    │                ▼                │
                                                    │  ┌───────────────────────────┐  │
                                                    │  │ Целевой плеер / сервис:   │  │
                                                    │  │ • SmartTube (YouTube)     │  │
                                                    │  │ • Vimu / JustPlayer (HLS) │  │
                                                    │  │ • TorrServe (Magnet)      │  │
                                                    │  │ • TV Bro (Web PWA)        │  │
                                                    │  └───────────────────────────┘  │
                                                    └─────────────────────────────────┘
```

---

## 3. Модуль 1: Бесшовный запуск (Seamless Launch & Intent Dispatch)

### А. Локальный мост (VibeBridge Service)
В `MainActivity` лаунчера или вспомогательный легковесный сервис встраивается ультракомпактный слушатель сокета (raw Java `ServerSocket` без тяжелых зависимостей):
- Слушает `0.0.0.0:8989`.
- Активируется **только когда экран ТВ включен** (`ACTION_SCREEN_ON`). В режиме сна сокет закрывается, потребление CPU и батареи равно **0%**.
- Принимает команду по локальной сети:
  ```json
  POST /api/v1/play
  {
    "url": "https://youtu.be/dQw4w9WgXcQ",
    "title": "Rick Astley - Never Gonna Give You Up",
    "position_ms": 42000,
    "source_type": "youtube"
  }
  ```

### Б. Умный роутинг в установленные приложения (Intent Dispatcher)
При получении ссылки лаунчер мгновенно отправляет Android Intent в нативное приложение на стике:

1. **YouTube-ссылки**:
   - Роутинг в **SmartTube**:
     ```java
     Intent intent = new Intent(Intent.ACTION_VIEW);
     intent.setData(Uri.parse(url));
     intent.setPackage("com.liskovsoft.smarttubetv.beta");
     intent.putExtra("time", positionMs / 1000);
     startActivity(intent);
     ```
2. **Прямые видеопотоки (MP4, MKV, HLS .m3u8)**:
   - Роутинг в **Vimu Player** или **JustPlayer**:
     ```java
     Intent intent = new Intent(Intent.ACTION_VIEW);
     intent.setDataAndType(Uri.parse(streamUrl), "video/*");
     intent.putExtra("position", positionMs); // Стандартный Android media position extra
     intent.putExtra("return_result", true);
     startActivity(intent);
     ```
3. **Magnet-ссылки и торренты**:
   - Роутинг в **TorrServe**: передача хэша/ссылки через локальный API `127.0.0.1:8090/torrent/upload` с мгновенным запуском воспроизведения.
4. **Обычные веб-страницы**:
   - Открытие в ТВ-браузере (**TV Bro** / **JioPages**) в режиме полноэкранного просмотра.

---

## 4. Модуль 2: Продолжение видео (Cross-Device Resume & Watch Next)

### А. Перехват в браузере (HTML5 Video Observer)
Браузерное расширение или букмарклет отслеживает состояние воспроизведения:
```javascript
// Автоматический сбор метаданных при паузе или переключении вкладки
const video = document.querySelector('video');
if (video) {
  const payload = {
    url: window.location.href,
    title: document.title,
    currentTime: Math.floor(video.currentTime),
    duration: Math.floor(video.duration),
    poster: video.poster || null
  };
  // Отправка на ТВ-стик
  fetch(`http://${TV_IP}:8989/api/v1/resume-session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
}
```

### Б. Полка «Продолжить просмотр» в лаунчере
- На домашнем экране над или под доком появляется элегантная полупрозрачная карточка:
  - Превью видео, название, прогресс-бар (например, `42% • осталось 14 мин`).
  - При нажатии [OK] на пульте видео мгновенно запускается на плеере стика ровно с сохраненной секунды.

---

## 5. Модуль 3: Единый дизайн-код (Unified Glass Design System)

Для обеспечения визуальной гармонии между ТВ и веб-интерфейсом используется единая дизайн-система **Vibe Glass System**:

| Токен | Значение на ТВ (Android Java) | Значение в браузере (CSS / Web) | Назначение |
|---|---|---|---|
| `--vibe-surface` | `#000000` (OLED Deep Void) | `#000000` | Фоновая основа интерфейса |
| `--vibe-dock` | `#141418` | `rgba(20, 20, 24, 0.85)` | Подложка дока и панелей |
| `--vibe-glass-specular` | Gradient: `0x3AFFFFFF` -> `0x0AFFFFFF` | `linear-gradient(180deg, rgba(255,255,255,0.22) 0%, rgba(255,255,255,0.04) 100%)` | Стеклянный градиент карточки |
| `--vibe-rim-stroke` | `0xE6FFFFFF` (1.2dp stroke) | `1px solid rgba(255, 255, 255, 0.35)` | Острый световой контур стекла |
| `--vibe-radius` | `16dp` – `20dp` | `16px` – `20px` | Радиус скругления углов карточек |
| `--vibe-font-sans` | Outfit (`@font/outfit`) | `'Outfit', -apple-system, sans-serif` | Шрифт интерфейса |
| `--vibe-spring` | Stiffness: `260`, Damping: `0.58` | `cubic-bezier(0.34, 1.56, 0.64, 1)` | Физика отклика при наведении/фокусе |

---

## 6. Пошаговый план внедрения VibeBridge

1. **Фаза 1: Внедрение веб-интерфейса (PWA Companion)**
   - Простая веб-страница на HTML/JS/CSS с общим дизайн-кодом.
   - Позволяет вставить любую ссылку, выбрать плеер и нажать «Play on TV Stick».
2. **Фаза 2: Легковесный ресивер в `tv-vibehack`**
   - Добавление `BridgeServer.java` в лаунчер (~150 строк кода без сторонних библиотек).
   - Обработка интентов и запуск плееров с таймкодом.
3. **Фаза 3: Расширение для Chrome/Firefox**
   - Кнопка на панели браузера: «Смотреть на Xiaomi Stick 4K».
   - Автоматическое определение таймкода HTML5-плеера и передача в локальную сеть.
