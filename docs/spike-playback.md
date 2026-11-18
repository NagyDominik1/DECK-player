> Tisztított változat a Deck privát repójából (magyarul; az angol összefoglaló a fő README-ben van).
> Titkok, belső hivatkozások és projektazonosítók nélkül.

> Utóirat: a döntés az lett, hogy a keresés a YouTube Data API-n megy, és a találatot a `playFromUri` indítja.

# Spike: konkrét szám / playlist indítása a YT Musicban

**Kérdés:** el tudja-e indítani a Deck egy KONKRÉT számot vagy playlistet a YouTube Musicban?
Ezen múlik a gyorsindítás (Phase 4) és a buli mód automatikus következő száma (Phase 6).

**Rövid válasz: igen.** A `MediaController.transportControls.playFromUri()` egy
`https://music.youtube.com/watch?v=<videoId>` linkkel a háttérben, a YT Music előtérbe hozása
nélkül elindítja pontosan a kért számot. Sorba állítani ("lejátszás következőként") viszont nem lehet.

## Tesztkörnyezet

| | |
|---|---|
| Telefon | Galaxy S25 (SM-S931B), Android 17, One UI 9.0 |
| YT Music | 9.45.51 (`com.google.android.apps.youtube.music`) |
| Dátum | 2026-11-15 |
| Módszer | Egy ideiglenes fejlesztői gombsor az appban (azóta törölve), adb-vel nyomkodva; az eredmény a logcatből, a `dumpsys media_session` kimenetéből és képernyőképekből jön. Minden teszt más számot indít, így egyértelmű, melyik módszer mit indított. |

Amit a YT Music session magáról hirdet (`PlaybackState.actions = 2600887`, `flags = 3`):

| Képesség | Hirdeti? |
|---|---|
| `ACTION_PLAY_FROM_SEARCH` | igen |
| `ACTION_PLAY_FROM_MEDIA_ID` | igen |
| `ACTION_PLAY_FROM_URI` | igen |
| `ACTION_SKIP_TO_QUEUE_ITEM` | nem (de működik, lásd lent) |
| `FLAG_HANDLES_QUEUE_COMMANDS` (sor szerkesztése) | nem |
| Sor olvasása (`controller.queue`) | igen, 25 elemes ablak, címe "Következő" |

Tanulság: a hirdetett képességek nem megbízhatóak. Kettő a háromból hirdetve van, mégsem működik,
egy pedig nincs hirdetve, mégis működik. Mindent ki kellett próbálni.

## Eredmények

"Háttér" = a YT Music fut és van aktív sessionje (szól vagy szünetel), de a Deck van előtérben.
"Bezárva" = `am force-stop` után, nincs session.

| # | Módszer | Működik? | Jó szám? | Előtérbe hozza a YT Musicot? | Megjegyzés |
|---|---|---|---|---|---|
| 1 | `playFromSearch("Daft Punk One More Time", extras)` (FOCUS + ARTIST + TITLE) | **Nem** | – | nem | Szünetelő lejátszásnál a session `ERROR` állapotba ment: "A szám nem játszható le." Lejátszás közben csendben figyelmen kívül hagyta. |
| 1b | `playFromSearch("Daft Punk Get Lucky", null)` | **Nem** | – | nem | Semmi nem történt, hiba sincs. |
| 2a | `playFromMediaId("dQw4w9WgXcQ")` (YouTube videóazonosító) | **Nem** | – | nem | Semmi nem történt. A YT Music saját, belső mediaId-formátumot várhat; ezt nem ismerjük. |
| 2b | `playFromUri("https://music.youtube.com/watch?v=9bZkp7q19f0")` | **Igen** | igen (Gangnam Style) | **nem** | Szünetelő és éppen szóló állapotból is azonnal vált. A sor lecserélődik a szám "rádiójára" (25 elem). |
| 2d | `playFromUri(".../watch?v=FTQbiNvZqaY&list=RDAMVMFTQbiNvZqaY")` | **Igen** | igen (Toto – Africa) | nem | Szám + lista együtt: a szám indul, a lista adja a folytatást. |
| 2g | `playFromUri(".../watch?v=<id>&list=PL…")` nyilvános playlisttel | **Igen** | a lista indult | nem | A megadott videó nem volt a listában, ezért a playlist első száma indult (BELLAKEO). Vagyis a `list` paraméter az erősebb. |
| 2h | `playFromUri(".../watch?list=PL…")` (csak lista, videó nélkül) | **Igen** | a lista első száma | nem | **Így lehet playlistet indítani a háttérben.** |
| 2c | `playFromUri(".../playlist?list=LM")` (Kedvelt zenék) | **Nem** | – | nem | Semmi nem történt. |
| 2f | `playFromUri(".../playlist?list=PL…")` nyilvános playlist | **Nem** | – | nem | A `playlist?list=` URL-forma nem megy, csak a `watch?list=`. |
| 2e | `playFromUri("https://www.youtube.com/watch?v=…")` | **Nem** | – | nem | Csak a `music.youtube.com` hoszt működik. |
| 3 | Intent `MEDIA_PLAY_FROM_SEARCH` a YT Music csomagra | **Részben** | – | **igen** | Megnyitja a keresési találatokat ("Queen Bohemian Rhapsody"), de **nem indít el semmit**; a felhasználónak kell rábökni. |
| 4 | Intent `ACTION_VIEW` `.../watch?v=kJQP7kiw5Fk` | **Igen** | igen (Despacito) | **igen** | Bezárt YT Musicnál is működik: elindítja az appot és a számot. |
| 5a | Intent `ACTION_VIEW` `.../playlist?list=LM` | **Részben** | – | igen | Megnyitja a playlist oldalát (Lájkolt zenék), de nem indítja el. |
| 5b | Intent `ACTION_VIEW` `.../watch?v=djV11Xbc914&list=RDAMVM…` | **Igen** | igen (Take On Me) | igen | Szám + lista, elindul. |
| 6a | `skipToQueueItem(queueId)` a YT Music saját sorában | **Igen** | igen (Cola Song) | nem | A sor már benne lévő elemeire lehet ugrani, pedig a session nem hirdeti. |
| 6b | Sorhoz adás / "lejátszás következőként" | **Nincs rá mód** | – | – | A session nem kezeli a queue-parancsokat (`FLAG_HANDLES_QUEUE_COMMANDS` hiányzik), és nincs rá intent sem. Nem próbáltam "erőből" elküldeni a parancsot, mert a hivatalos kliens (MediaControllerCompat) is megtagadja ilyen sessionnél. |

### Háttérben / bezárva

| Állapot | `playFromUri` (vezérlő) | `ACTION_VIEW` (intent) |
|---|---|---|
| YT Music háttérben, szól | működik, háttérben marad | működik, előtérbe jön |
| YT Music háttérben, szünetel | működik, háttérben marad | működik, előtérbe jön |
| YT Music bezárva (force-stop) | **nem megy: nincs session, nincs mit vezérelni** | működik: elindítja az appot és a számot, előtérbe jön |
| Közvetlenül a hidegindítás után | működik (a session létrejött) | – |

További megfigyelések:
- Force-stop után a `MEDIA_PLAY` médiagomb-esemény **nem** éleszti fel a YT Musicot (nem jön létre session).
  Háttérből tehát nem tudjuk elindítani a bezárt appot; ehhez az `ACTION_VIEW` kell, ami előtérbe hozza.
- **Nem teszteltem:** a "Legutóbbiak" közül kihúzott (nem force-stoppolt) YT Musicot, valamint azt, hogy a
  `playFromUri` akkor is megy-e, amikor a Deck maga van háttérben vagy a képernyő le van zárva. A vezérlőhívásra
  elvileg nem vonatkozik háttérkorlátozás (az `startActivity`-re igen!), de a Decknek ehhez életben kell maradnia
  (előtér-szolgáltatás), és ezt a Phase 6 elején ki kell próbálni.
- A videóazonosító sima YouTube-videóazonosító (ugyanaz, amit a YouTube Data API v3 keresés visszaad),
  tehát a Phase 5 keresése közvetlenül használható bemenetnek.

## Ajánlás

**A buli mód "következő szám" funkcióját a `playFromUri("https://music.youtube.com/watch?v=<videoId>")`
hívásra érdemes építeni.** Ez az egyetlen módszer, ami a pontos számot indítja, a háttérben, a YT Music
előtérbe hozása nélkül.

Mivel sorba állítani nem lehet, a sort a Decknek kell vezetnie (saját queue):

1. A vendég kér egy számot → a Deck a saját sorába teszi (Supabase), a videóazonosítóval együtt.
2. A Deck figyeli a pozíciót (ezt már most is tudja), és a szám vége előtt kb. 1-2 másodperccel
   meghívja a `playFromUri`-t a sor következő elemére. ("auto-switch near track end")
3. Biztonsági háló: ha a váltás lekésett és a YT Music már a saját rádiójából indított valamit
   (a metaadat megváltozott, de nem a mi számunkra), azonnal `playFromUri` a miénkre.
4. Ha a saját sor üres, nem csinálunk semmit: a YT Music megy tovább a saját sorával.

Kockázatok és korlátok:
- **Nem lesz átúsztatás:** a váltás éles vágás, és a szám utolsó 1-2 másodperce elveszik.
- **Kell aktív session.** Ha a YT Music be van zárva, a buli mód indításakor egyszer előtérbe kell hozni
  (`ACTION_VIEW` az első számra, vagy `getLaunchIntentForPackage`), utána már minden mehet a háttérből.
- **Nem dokumentált viselkedés.** A `playFromUri` támogatása a YT Music belső döntése, egy frissítés
  elronthatja. Érdemes a hívás után pár másodperccel ellenőrizni, hogy tényleg a kért szám szól-e,
  és ha nem, tartalékként `ACTION_VIEW`-val próbálkozni (ez előtérbe hozza a YT Musicot).
- A Decknek a buli alatt a háttérben is futnia kell → előtér-szolgáltatás + a Samsung
  akkumulátor-optimalizálás alóli kivétel (az onboarding kéri).

A gyorsindításhoz (Phase 4):
- **Playlist gombok:** `playFromUri(".../watch?list=<playlistId>")`, ha van session; különben
  `ACTION_VIEW` ugyanerre a linkre. A saját playlistekkel (pl. `LM`, Kedvelt zenék) a `watch?list=` forma
  később kipróbálva: működik.
- **Keresőmező:** a `MEDIA_PLAY_FROM_SEARCH` csak a találati listáig visz el, nem indít el semmit.
  Az eredeti Phase 4 terv erre épített, ezért újra kellett gondolni: vagy elfogadjuk, hogy a keresés a
  YT Musicban ér véget, vagy a keresést előrehozzuk a YouTube Data API-ra (Phase 5), és a találatot
  `playFromUri`-val indítjuk.
