> A Deck privát repójából tisztított változat. English version: [spike-background-start.md](spike-background-start.md).
> Titkok, belső hivatkozások és projektazonosítók nélkül.

# Spike: a YouTube Music indítása a háttérben, megnyitás nélkül

**Cél:** ha nincs YouTube Music média-session, a Deck ne nyissa meg a YouTube Musicot (a view intent előtérbe
hozza), hanem a háttérben keltse életre, és utána a szokásos `playFromUri`-val indítsa a kért számot. A YouTube
Music képernyője minél ritkábban jelenjen meg.

**Eredmény:** minden „bezárt” állapotban működik, lezárt képernyővel és háttérben lévő Deckkel is. A kért szám
az indítás után 1,1–1,6 mp-cel szól, előtérbe semmi nem jön. A view intent már csak tartalék.

| | |
|---|---|
| Telefon | Galaxy S25 (SM-S931B), Android 17 / One UI |
| YT Music | 9.46.51 |
| Dátum | 2026-11-18, utóellenőrzés 2026-11-19 |
| Módszer | Csak a debug buildben lévő broadcast receiver (a kiadott APK-ban nincs), adb-ről lépésenként; az eredmény a logcatből és a `dumpsys media_session` kimenetéből |

## Módszerek

| Módszer | Működik? | Idő | Megjegyzés |
|---|---|---|---|
| Kapcsolódás a YouTube Music média-böngésző szolgáltatásához | **Elutasítja** | 8 ms (élő folyamat), 130–190 ms (leállított) | A YouTube Music csak a Google saját klienseit engedi be. **A mellékhatás a kulcs:** a bind force-stop után is elindítja a YouTube Music folyamatát, és az létrehoz egy *inaktív* sessiont. A `getActiveSessions` ezt még nem adja vissza, vezérelni még nem lehet. |
| Média PLAY billentyű (`AudioManager.dispatchMediaKeyEvent`) | **Igen**, ha a YouTube Music a médiabillentyűk címzettje | a session 100–250 ms-mal a billentyű után jelenik meg | Az inaktív session az utolsó számmal felébred; a Deck azonnal megállítja, majd `playFromUri`. A régi szám nem hallatszik (állapotok: NONE → STOPPED → PLAYING az új számmal). Force-stop után a billentyű **egyedül nem megy** (a leállított app nem kap broadcastot), előtte bind kell. A bind előtt küldött billentyű elveszik. |
| Explicit `ACTION_MEDIA_BUTTON` broadcast a YouTube Music receiverének | **Szándékosan kihagyva** | – | Android 12 óta a háttérben lévő app nem indíthat előtérbeli szolgáltatást egy másik app broadcastjából. A YouTube Music receivere pont ezt tenné: rossz esetben a YouTube Music összeomlik. Nem éri meg a kockázatot. |
| View intent a linkkel (a régi út) | Igen | ~2–3 mp | Előtérbe hozza a YouTube Musicot. Már csak tartalék. |

## Mérések állapotonként (bind → billentyű → `playFromUri`)

| Állapot | Előállítás | Mi kell | Session | A kért szám szól | Előtérbe jön? |
|---|---|---|---|---|---|
| (a) szünetel, a session él | szünet parancs | semmi (eddig is ment) | van | azonnal | nem |
| (b) a folyamat él, a session inaktív | force-stop + bind, billentyű nélkül | billentyű | +166 ms | +1146 ms | nem |
| (c1) kihúzva / a rendszer kilőtte | `am kill` szünet után | billentyű (bind sem kell) | +261 ms | +1245 ms | nem |
| (c2) force-stop | `am force-stop` | bind, utána billentyű | +609 ms | +1580 ms | nem |
| (c2) lezárt képernyővel | force-stop + alvás gomb | ugyanaz | +611 ms | +1638 ms | nem |
| (c2) billentyű bind nélkül | force-stop, csak billentyű | – | **nincs** | – | nem |
| (c2) billentyű +8 ms-nál (a bind válasza előtt) | force-stop | – | **nincs** (elveszett) | – | nem |

További megfigyelések:
- Szünet után a YouTube Music legalább 9 percig aktívan tartja a sessiont, de az előtérbeli szolgáltatását
  azonnal leállítja, így a rendszer bármikor kilőheti a folyamatot. A gyakorlatban a (c1) a gyakori.
- Elindulás után a YouTube Music a saját előtérbeli szolgáltatásával tartósan szól.

## A billentyű időzítése

A billentyűnek kell egy kis idő a bind után. Azonos telefonon, force-stopolt YouTube Musickal a 450 ms-os PLAY
0/3, az 500 ms-os 3/3 esetben ébresztette fel a sessiont. Az első billentyű ezért legkorábban 500 ms-nál megy,
1,2 mp múlva egy ismétlés jön (a PLAY nem kapcsoló, a második nem árt), és 3 mp után a Deck feladja. Minden
működő esetben 0,2–0,6 mp alatt megjött a session, így a 3 mp bőven elég.

A döntések egy kis, tiszta Kotlin tervben vannak, saját egységtesztekkel (mikor mehet a billentyű, mikor jön az
ismétlés, mikor kell feladni). Az Android-oldal csak végrehajtja, és egyszerre csak egy ébresztés fut.

## Biztonság: a billentyű csak a YouTube Musichoz érhet

Az Android a PLAY billentyűt annak az appnak adja, amelyet épp a médiabillentyűk címzettjének tart, és ez nem
feltétlenül a YouTube Music. A Deck minden PLAY előtt (az ismétlés előtt is) lekéri a rendszertől ezt a címzettet
(`MediaSessionManager.getMediaKeyEventSessionPackageName()`, Android 13-tól), és csak akkor küldi a billentyűt, ha
az a YouTube Music. Az első változat abból döntött, amit a Deck utoljára játszani látott. A telefonon emiatt
megtagadta a billentyűt, mert utoljára a sima YouTube szólt, pedig az Android már a YouTube Musicnak címezte a
médiabillentyűket. A rendszer megkérdezése ezt megoldotta. Android 12-n, ahol ez a hívás nincs, a Deck az utoljára
látott lejátszót ellenőrzi, és azt, hogy más appnak ne legyen aktív sessionje. Ha mégis másik app indulna el, a
Deck megállítja, és a tartalékra vált.

## Az appban

Minden zeneindítási pont előbb ugyanezt az ébresztést használja: keresési találat, kezdőlapi kártyák, könyvtár,
MIX, a Deck saját sora (lezárt képernyővel is) és a buli. Ha nem sikerül, a Deck jelzi, és ugyanonnan újra lehet
próbálni. A YouTube Music megnyitása külön, kifejezett gomb a beállításokban.

| Eset | Napló | Eredmény |
|---|---|---|
| Kezdőlapi kártya, YouTube Music force-stop, Deck előtérben | bind elutasítva +163 ms, billentyű +469 ms, session +632 ms | szól a szám, a Deck előtérben marad |
| Saját sor, YouTube Music kilőve, Deck háttérben, **lezárt képernyő** | bind +120 ms, billentyű +455 ms, session +565 ms | szól a sor eleje, nincs „koppints ide” értesítés |

## Ismert korlátok

- **Soha nem játszott YouTube Music** (friss telepítés, üres előzmény): nem próbáltuk ki, mert ehhez a YouTube
  Music adatait törölni kellett volna. Valószínűleg a billentyűnek nincs mit folytatnia, és 3 mp után a Deck
  jelzi, hogy nem indult el.
- Egy jövőbeli YouTube Music frissítés megváltoztathatja a bind vagy a billentyű viselkedését. Ilyenkor a 3 mp-es
  korlát lejár, és a Deck a tartalékra vált: rosszabb nem lehet, mint korábban.
