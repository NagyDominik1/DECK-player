> Tisztított változat a Deck privát repójából (magyarul; az angol összefoglaló a fő README-ben van).
> Titkok, belső hivatkozások és projektazonosítók nélkül.

# Buli mód ("Aux") – teljes terv (Phase 6A + 6B)

Állapot: megépült és fut (szerver, vendég weboldal, a Deck host oldala).

## 1. Mi ez egy mondatban

A host a Deckben bulit indít, a vendégek QR-kóddal egy weboldalra jutnak, ahol számot kérhetnek és
szavazhatnak; a sort egy Postgres adatbázis tartja, a sorrendet a szerver számolja, a Deck pedig a szám
vége előtt ~1,5 mp-cel elindítja a sor elejét a YT Musicban.

```
  Vendég telefonja (böngésző)            Supabase                             Host telefonja
 ┌──────────────────────────┐      ┌─────────────────────────────┐      ┌──────────────────────┐
 │ party-web (Vercel)       │      │ Postgres                    │      │ Deck (6B)            │
 │  index.html + app.js     │ RPC  │  táblák + RLS               │ RPC  │  buli képernyő, QR   │
 │  - olvas: REST (SELECT)  │─────▶│  szabályok: SECURITY DEFINER│◀─────│  host_* függvények   │
 │  - ír: guest_* RPC       │      │  függvények                 │      │  host_secret-tel     │
 │  - élő: Realtime (WS)    │◀─────│  Realtime (postgres_changes)│─────▶│  sor figyelése       │
 │  - keresés: Edge Function│─────▶│ Edge Function: search ──────┼──▶ YouTube Data API       │
 └──────────────────────────┘      └─────────────────────────────┘      │  playFromUri → YT Music
                                                                        └──────────────────────┘
```

Három szereplő, három "kulcs":

| Ki | Mivel azonosítja magát | Hol él a titok | Mire jó |
|---|---|---|---|
| Bárki | publishable (anon) kulcs | a weboldalban, publikus | olvasás + az RPC-k meghívása (a függvény dönt) |
| Vendég | `guest_token` (véletlen, a szerver adja belépéskor) | a böngésző `localStorage`-ében | kérés, szavazás, keresés a saját nevében |
| Host | `host_secret` (véletlen, a szerver adja a party létrehozásakor) | a Deck privát tárhelyén | átrendezés, törlés, léptetés, buli vége |
| Deck app | app-kulcs (create key) | az appba fordítva, ezért publikusnak tekintjük | új party létrehozása; a kvótát projekt-szintű plafonok védik (5. pont) |

Az adatbázisban minden titoknak csak a SHA-256 hash-e van meg. A titkok 244 bit véletlent tartalmaznak
(két UUID v4), ezért sózás és lassú hash (bcrypt) nem kell: nincs mit szótárból kitalálni.

## 2. Adatmodell

Migrációk: `supabase/migrations/`. Minden tábla a `public` sémában RLS-sel; a `private` séma nincs kitéve az API-nak.

| Tábla | Fontos oszlopok | Vendég olvashatja? |
|---|---|---|
| `parties` | `id` (uuid), `code` (4 betű, az aktívak között egyedi), `host_secret_hash`, `status` (active/ended), `now_playing` (jsonb), `settings` (jsonb), `search_count`, `next_arrival`, `created_at`, `ended_at` | igen, de a `code`, `host_secret_hash`, `next_arrival` oszlopot nem (oszlopszintű GRANT) |
| `party_guests` | `id`, `party_id`, `nickname`, `token_hash`, `search_count`, `last_request_at`, `created_at` | nem (a becenév a sor elemére van másolva) |
| `party_tracks` | `party_id`, `playlist_id`, `playlist_title`, `position`, `video_id`, `title`, `artist`, `thumb`, `duration_ms` | igen |
| `queue_items` | `id`, `party_id`, `video_id`, `title`, `artist`, `thumb`, `duration_ms`, `requested_by` (vendég id; null = host), `requested_by_name`, `arrival_index`, `net_votes`, `host_rank`, `status` (queued/playing/played/removed), `created_at` | igen |
| `votes` | (`item_id`, `guest_id`) elsődleges kulcs, `value` (+1/-1) | nem (a saját szavazatait a `guest_state` adja vissza) |
| `private.videos` | a keresésben látott videók adatai (`video_id`, cím, előadó, borító, hossz) | nem |
| `private.search_cache` | normalizált keresés → találatok, 24 óráig | nem |
| `private.app_secrets` | az app-kulcs hash-e | nem |

`now_playing` alakja: `{ item_id, video_id, title, artist, thumb, duration_ms, position_ms, is_playing, started_at }`.
A `started_at` az a szerveridő, amikor a `position_ms` érvényes volt; a vendégoldal ebből számolja a
folyamatjelzőt: `pozíció = position_ms + (most - started_at)`, ha `is_playing`. Így a szünet és a tekerés is leírható.

`settings` (party létrehozásakor felülírható, alapértékek):
`search_per_guest` 5, `search_per_party` 40, `max_queued_per_guest` 3, `request_cooldown_s` 30, `max_guests` 50.

Adatbázis-szintű biztosítékok (akkor is tartanak, ha egy függvényben hiba lenne):
- egyedi index: `(party_id, video_id)` ahol `status in ('queued','playing')` → nincs duplikátum a sorban;
- egyedi index: `code` ahol `status = 'active'` → két aktív bulinak nem lehet ugyanaz a kódja;
- `votes` elsődleges kulcsa `(item_id, guest_id)` → egy vendég egy számra egy szavazat;
- egyedi index: `(party_id, lower(nickname))` → egy bulin belül nincs két egyforma becenév.

## 3. Jogosultságok: ki mit érhet el

A projektben az "Automatically expose new tables" ki van kapcsolva, ezért a migrációk mindent explicit adnak meg:

- **Táblák**: az `anon` szerep csak `SELECT`-et kap, és csak arra, ami a vendégoldalhoz kell
  (`parties` megengedett oszlopai, `queue_items`, `party_tracks`, `queue_view`). `INSERT/UPDATE/DELETE` joga
  senkinek nincs az API felől. Az `authenticated` szerep semmit nem kap, mert nincs bejelentkezés.
- **RLS**: minden táblán be van kapcsolva. A három olvasható táblán `SELECT` policy van az `anon` szerepnek;
  a `party_guests` és `votes` táblán nincs policy, tehát kívülről láthatatlan.
- **Írás**: kizárólag `SECURITY DEFINER` függvényeken át (a függvény a tulajdonosa jogaival fut, így ő
  írhat; a hívó csak azt tudja megtenni, amit a függvény megenged). Minden függvény `search_path = ''`-rel fut
  (nem lehet más sémából becsempészett objektummal eltéríteni), és az `EXECUTE` jog explicit: a vendég- és
  host-függvényekre `anon`, a keresés belső függvényeire csak `service_role`.
- **Párhuzamosság**: minden író függvény először zárolja a party sorát (`SELECT ... FOR UPDATE`), így egy
  bulin belül az írások sorban futnak: a limitek és az `arrival_index` versenyhelyzet nélkül számolhatók.

Miért `using (true)` az olvasási policy? A vendégnek nincs bejelentkezése, a Realtime pedig csak a szerep
alapján tud RLS-t ellenőrizni, ezért sor szinten nem tudjuk a "saját" bulijára szűkíteni. Amit így bárki
olvashat: számcímek és becenevek. Amit nem: a belépőkód, a tokenek, a hash-ek, és hogy ki mire szavazott.
Írni a kód ismerete nélkül nem lehet, mert vendég token csak `guest_join(kód)`-dal szerezhető.

## 4. Függvények (RPC)

Minden függvény JSON-t ad vissza: `{ "ok": true, ... }` vagy `{ "ok": false, "error": "<kód>", ... }`.
A szerver hibakódot ad, a magyar szöveget a kliens írja ki (weboldal: `app.js`, Deck: `strings.xml`).

| Függvény | Ki hívja | Mit csinál |
|---|---|---|
| `create_party(create_key, settings)` | Deck | takarít, kódot, host_secretet és link-tokent generál; visszaadja: `party_id`, `code`, `host_secret` (csak most, egyszer), `link_token` |
| `guest_peek(code, link?)` | vendég | név előtt: él-e még a linkben szereplő buli (kód + link-token; token nélkül, kézzel beírt kódnál a kód is elég) |
| `guest_join(code, nickname, link?)` | vendég | becenév ellenőrzése, vendég létrehozása; rossz link-tokennel `party_not_found`; visszaadja: `party_id`, `guest_id`, `guest_token` |
| `guest_state(party_id, token)` | vendég | saját szavazatok, hátralévő keresések, cooldown, szerveridő |
| `guest_request(party_id, token, video_id)` | vendég | szám kérése (szabályok: 5. pont) |
| `guest_vote(party_id, token, item_id, value)` | vendég | +1 / -1 / 0 (visszavonás); saját kérésre nem lehet |
| `guest_cancel(party_id, token, item_id)` | vendég | saját, még sorban álló kérés visszavonása |
| `host_state(party_id, secret)` | Deck | party, vendégek, rendezett sor egyben |
| `host_set_tracks(party_id, secret, tracks, replace)` | Deck | playlist-számok feltöltése (darabokban, max. 5000) |
| `host_add(party_id, secret, track)` | Deck | a host tesz számot a sorba |
| `host_move(party_id, secret, item_id, position)` | Deck | kézi átrendezés (rögzítés, 6. pont) |
| `host_unpin(party_id, secret, item_id)` | Deck | rögzítés feloldása |
| `host_remove(party_id, secret, item_id)` | Deck | törlés a sorból |
| `host_advance(party_id, secret, item_id?)` | Deck | következő szám (vagy egy megadott elem) → `playing`, `now_playing` beállítása |
| `host_set_now_playing(party_id, secret, now)` | Deck | most szóló szám / szünet / tekerés jelentése |
| `host_end(party_id, secret)` | Deck | buli vége, adatok törlése |
| `search_begin / search_finish / search_fail` | csak az Edge Function (`service_role`) | keresési limit foglalása, cache, visszatérítés |
| `cover_live_parties()` | csak a `cover` Edge Function (`service_role`) | az aktív bulik id-jai (a takarításhoz) |
| `test_age_party / test_run_cleanup` | csak a tesztek (teszt-kulccsal) | egy TESZT buli "öregítése" és a takarítás lefuttatása |

A vendég csak olyan `video_id`-t kérhet, amit a szerver ismer: a party `party_tracks` sorai közül vagy egy
korábbi keresés találatai közül (`private.videos`). A címet, előadót, borítót a szerver másolja a sorba, a
vendég nem küldhet saját szöveget, így nem lehet hamis címmel vagy szeméttel teleírni a sort.

## 5. Szabályok spam ellen

| Szabály | Hol van kikényszerítve | Hibakód |
|---|---|---|
| csak aktív (és 12 óránál fiatalabb) partira lehet írni | minden függvény elején | `party_not_found` |
| becenév 1–20 karakter, trimmelve, vezérlőkarakterek nélkül, alap trágárságszűrés, bulin belül egyedi | `guest_join` | `bad_nickname`, `nickname_taken` |
| max. 50 vendég bulinként | `guest_join` | `party_full` |
| ugyanaz a `video_id` nem lehet kétszer a sorban (queued/playing) | `guest_request` + egyedi index | `duplicate` |
| max. 3 sorban álló szám vendégenként | `guest_request` | `too_many` |
| 30 mp két kérés között vendégenként | `guest_request` | `cooldown` (+ `wait_s`) |
| egy vendég egy számra egy szavazat (módosítható, visszavonható) | `guest_vote` + elsődleges kulcs | – |
| saját kérésre nem lehet szavazni | `guest_vote` | `own_item` |
| vendégenként 5, bulinként 40 keresés | `search_begin` | `search_limit_guest`, `search_limit_party` |
| host-művelet csak a `host_secret`-tel | minden `host_*` függvény | `forbidden` |
| party létrehozása csak az app-kulccsal | `create_party` | `forbidden` |
| az egész projektre: max. 2 buli egyszerre, 10 buli/nap, 50 vendégkeresés/nap (Phase 8, a kulcs az APK-ban publikus) | `create_party`, `search_begin` | `too_many_parties`, `party_limit_day`, `search_limit_day` |

Őszinte korlát: bejelentkezés nélkül a "vendég" egy böngésző-token. Aki törli a böngésző adatait, új vendégként
léphet be, és újra kap 3 kérést. Ezt bulinkénti plafonok fogják meg (50 vendég, 40 keresés), és a host
bármit törölhet. Egy házibulihoz ez elég; a nyilvános internethez nem lenne az.

## 6. Sorrend

A sorrendet **a szerver** számolja a `queue_view` nézetben (`position` oszlop); a kliensek csak
megjelenítik. Így a weboldal, a Deck és a tesztek biztosan ugyanazt látják.

**1. szabály – érkezés.** Minden kérés kap egy `arrival_index`-et (1, 2, 3, … bulinként). Szavazatok
nélkül ez a sorrend.

**2. szabály – szavazatok: egy szavazat = pontosan egy hely.** `net_votes` = like-ok − dislike-ok (ez a
kijelzett pontszám). A sorrendet viszont nem képlet adja, hanem a **tárolt helyzet** (`free_rank`): minden
szavazás annyi helyet mozdít a számon, amennyit a vendég szavazata változott, a szomszédjával cserélve:

| A vendég szavazata | Mozgás |
|---|---|
| nincs → like | 1 hely fel |
| nincs → dislike | 1 hely le |
| like → dislike | 2 hely le |
| dislike → like | 2 hely fel |
| like / dislike visszavonva | 1 hely vissza |
| ugyanaz még egyszer | semmi |

Az első szabad szám feljebb, az utolsó lejjebb nem megy (a szavazat ettől még számít a pontszámban). Új kérés
mindig a szabad sor végére kerül. Döntetlen nem lehet: a `free_rank` minden várakozó számnál különböző.

*Miért nem képlet (Phase 10 javítás)?* Korábban `kulcs = arrival_index − net_votes × 1,5` volt. Egy like csak
akkor vitt át a felette lévőn, ha a kettejük érkezési száma 1-nél kevesebbel tért el, de ez gyakran nem igaz:
az elhangzott, törölt, visszavont számok lyukat hagynak, és egy átszavazott szám is. Példa: A1 B2 C3 D4,
like C-re → A C B D; like D-re: D kulcsa 4 − 1,5 = 2,5, B-é 2 → D **nem mozdult**, pedig közvetlenül B alatt
volt. Ugyanabban a helyzetben a sűrű sorszám (row_number érkezési sorrendben) sem segít, mert D ott is 4., B 2.
A tárolt helyzettel minden szavazás pontosan a szomszédot lépi át.

**3. szabály – a host mindig nyer.** Ha a host kézzel áthelyez egy számot, az `host_rank`-ot kap: ez a
**helyének sorszáma** a sorban (1 = következő). A rögzített szám azon a helyen marad, a szavazatok nem
mozdítják. A többi (nem rögzített) szám a tárolt helyzete szerinti sorrendben tölti ki a szabadon maradt
helyeket. Szavazáskor a szabad számok egymás közt cserélnek helyet; a rögzítettet "átugorják".

Hogy a rögzített helyek értelmesek maradjanak, amikor a sor változik:
- ha egy előrébb álló szám kikerül a sorból (elindul, törlik, visszavonják), a mögötte lévő rögzített számok
  `host_rank`-ja eggyel csökken (együtt lépnek előre a sorral);
- ha a host egy számot áthúz a rögzítettek fölött, a közbeesők eggyel elcsúsznak, pont úgy, ahogy a
  húzásnál a képernyőn látszik;
- új kérés mindig a végére érkezik, a rögzítetteket nem érinti.

### Példa (ezt futtatja a szerver "sorrend" tesztje is)

| Lépés | Mi történik | Szabad sor (a rögzítettek nélkül) | Sor |
|---|---|---|---|
| 1 | A, B, C, D, E érkezik ebben a sorrendben | A B C D E | A B C D E |
| 2 | E kap egy like-ot | E egy hellyel feljebb | A B C **E** D |
| 3 | E kap még egy like-ot | még egy hely | A B **E** C D |
| 4 | B kap egy dislike-ot | B egy hellyel lejjebb | A E **B** C D |
| 5 | A host D-t az 1. helyre húzza | D rögzítve: `host_rank` = 1; szabad: A E B C | **D** A E B C |
| 6 | D kap három dislike-ot | D rögzített, nem mozdul | D A E B C |
| 7 | C kap három like-ot (három vendégtől) | C három hellyel feljebb: C A E B | D **C** A E B |
| 8 | A host lépteti a sort (`host_advance`) | D szól; a sorból kikerült | C A E B |

A 7. lépés a lényeg: C a szabad sor elejére ért, mégsem előzi meg D-t, mert az 1. hely foglalt.

A "lyukas" eset és a gyors egymás utáni szavazások külön tesztje: "order: one like = exactly one place, also with
gaps and after other moves" (2 elhangzott/törölt szám, like-ok különböző számokon, like → dislike, visszavonás,
rögzített szám mellett, három vendég egyszerre szavaz).

### Hogyan számolja a nézet

1. A rögzített számok a `host_rank` helyükre kerülnek.
2. A szabad számok `free_rank` szerint sorszámot kapnak: k = 1, 2, 3, …
3. A k-adik szabad szám helye: `k + (ahány rögzített hely van előtte)`. Egy r helyre rögzített szám
   (a rögzítettek között j-edik) előtt `r − j` szabad hely van, tehát akkor előzi meg a k-adik szabad számot,
   ha `r − j < k`.
4. A végén a helyek szerint újraszámozunk (1, 2, 3, …), így akkor is folytonos a sorszám, ha egy rögzített
   hely a sor végén túlra mutatna.

## 7. Keresés vendégeknek (Edge Function: `search`)

`POST /functions/v1/search` `{ party_id, token, q }` → `{ ok, results, searches_left, party_searches_left, cached }`.

1. `search_begin`: ellenőrzi a vendéget; a keresést normalizálja (kisbetű, trim, többszörös szóköz → egy).
   Ha van 24 óránál frissebb cache → azonnal visszaadja, **nem számít a limitbe**. Különben ellenőrzi és
   lefoglalja a limitet (vendég +1, party +1).
2. YouTube Data API a szerver saját kulcsával (Edge Function secret): `search.list` (`type=video`, `videoCategoryId=10`,
   15 találat) + `videos.list` a hosszakért.
3. Ugyanaz a tisztítás, mint a Deckben (Phase 4): `TitleCleaner` logika (zajos zárójelek, "Előadó - Cím"
   szétszedése, "- Topic" levágása) és duplikátumszűrés (Topic csatorna > hivatalos csatorna > bármi más;
   a győztes az első előfordulás helyén marad). A Kotlin kód TypeScript-másolata az Edge Functionben.
4. `search_finish`: cache-be és a `private.videos` táblába írja a találatokat (innen tudja a `guest_request`,
   hogy a kért videó létezik).
5. Hiba esetén `search_fail` visszaadja a lefoglalt keresést. Kvótahiba → `error: "quota"`; a weboldal
   magyarul kiírja, és a Playlistek fül továbbra is működik (az nem használ YouTube API-t).

Kvóta: egy új keresés 101 egység (100 + 1). Egy buli legfeljebb 40 × 101 = 4040 egységet ér, a napi keret
10 000. A szerverkulcs külön Google Cloud kulcs (nem az appé), így a buli nem a Deck saját keresőjének keretét fogyasztja, ha külön projektben van.

## 8. Realtime

- A `supabase_realtime` publikációban: `parties` és `queue_items`. A vendégoldal két `postgres_changes`
  feliratkozást tart: `parties` (`id=eq.<party>`: most szól, buli vége, hátralévő keresések) és
  `queue_items` (`party_id=eq.<party>`).
- A Realtime ugyanazzal az `anon` szereppel és RLS-sel ellenőriz, mint a REST, ezért kell a `SELECT` jog és policy.
- A sorrendet nem az eseményekből rakja össze a kliens: bármilyen `queue_items` eseményre (kis késleltetéssel
  összevonva) újra lekéri a `queue_view`-t. Egyszerűbb, és a sorrend egyetlen helyen (a szerveren) számolódik.
- Törlés helyett státuszváltás (`removed`, `played`): a DELETE eseményekre a Realtime nem tud szűrni és RLS-t
  ellenőrizni, az UPDATE-re igen.
- Biztonsági háló: 20 mp-enként és a lap előtérbe kerülésekor (iPhone a háttérben bontja a WebSocketet) teljes frissítés.

## 9. Vendég weboldal (`party-web/`, Vercel: `deck-party`)

- `index.html` (jelölés + CSS) és `app.js`, framework és build nélkül; `supabase-js` CDN-ről.
- Cím: https://deck-party.vercel.app. A QR-link: `/?c=KQZT&p=k3m9x2qa` (kód + a buli saját link-tokenje, Phase 10).
  Először `guest_peek`: ha a linkben szereplő buli már nincs (a kódot közben új buli kaphatta meg), "A buli véget ért"
  oldal jön ("Másik buli kódja" gombbal); egy régi QR-kód így soha nem visz be egy későbbi bulira. Ha él: ha ehhez a
  bulihoz van mentett token, egyből be; különben becenév (az előző előre kitöltve). Kód nélkül kódbeíró mező; kézzel
  beírt kódnál elég a 4 betű (az csak aktív bulit talál).
- Borító: ha a "most szól"-nak nincs képe, Deck-kazetta grafika (CSS, `--cassette`); a később megérkező kép
  áttűnéssel cserélődik be. A sor bélyegképei ugyanígy.
- `localStorage`: `deck.nickname` (utolsó becenév), `deck.party.<KÓD>` (`party_id`, `guest_id`, `guest_token`).
- Felül "most szól" kártya; fülek: **Sor** (helyezés, kérő neve, 👍/👎, saját kérés kiemelve, visszavonható, szavazógombok nélkül) ·
  **Playlistek** (a host listái, szűrés listára és szövegre) · **Keresés** (hátralévő keresések kiírva).
- Kazetta stílus: ház #D8D9D3, billentyű #ECEDE7, vályú #C4C5BE, tinta #15161A, kobalt #2340D8, Archivo.
- Minden felhasználótól származó szöveg `textContent`-tel kerül a lapra (nincs HTML-beszúrás).

## 10. Életciklus és takarítás

- `host_end`: `status = ended`, a party minden adata (vendégek, sor, szavazatok, playlist-számok) azonnal törlődik.
  Maga a party sor (kód és titok nélkül értéktelen) még 1 óráig megmarad, hogy a nyitva maradt vendégoldalak
  "A buli véget ért" üzenetet kapjanak.
- `private.cleanup()` minden `create_party` elején ÉS óránként (pg_cron `deck-party-cleanup`, minden óra 7. percében):
  törli a lezárt (1 óránál régebbi) és a 12 óránál régebben létrehozott partikat mindenestül, a 24 óránál régebbi
  keresési cache-t és a 48 óránál régebbi videóadatokat. Utána ugyanez a feladat (pg_net) meghívja a `cover`
  Edge Function `cleanup` műveletét, ami a már nem aktív bulik borító-mappáit törli (SQL-ből a Storage fájl nem
  törölhető rendesen). A `cleanup` titok nélkül hívható: csak nem aktív bulik mappáit törölheti.
- Borítók (Phase 10): ha a szóló számnak nincs képlinkje, a Deck a borítót 300px-es JPEG-ként feltölti a `cover`
  Edge Functionnel (`upload`, a host titkával ellenőrizve) a publikus `party-covers` bucketbe, bulinként külön
  mappába (`<party_id>/<kulcs>.jpg`). Írni csak a függvény tud (nincs storage policy). A buli végén a Deck `purge`-öt
  hív, különben az óránkénti takarítás viszi. A kép CDN-gyorsítótára 60 mp: törlés után legfeljebb ennyi ideig érhető el.
- A 12 órás határ a takarítástól függetlenül is él: 12 óránál régebbi partira egyik függvény sem ír.

## 11. A Deck oldala röviden

- A host a playerről indít bulit (playlistek kiválasztása, `create_party`, `host_set_tracks` darabokban, QR-kód).
- A léptetést egy tiszta Kotlin motor dönti el (nincs benne Android, hálózat, óra), egy foreground service hajtja végre:
  a szám vége előtt ~1,5 mp-cel `playFromUri(watch?v=<a sor eleje>)`, utána `host_advance`. Előbb a lejátszás, aztán a
  hálózat: a váltás nem várhat a szerverre.
- Ha a YT Music magától (autoplay) más számot indít, és a sor nem üres, a motor azonnal felülírja a sor elejével.
  Üres sornál a YT Music megy tovább, a Deck csak jelenti, mi szól.
- Bizonytalan egyezés soha nem indít új számot (a YT Music nem ad videóazonosítót, csak címet).
- Realtime helyett lekérdezés: 3 mp, ha a host nézet látszik, 10 mp a háttérben.

## 12. Ismert korlátok

- A 4 betűs kód (24⁴ ≈ 330 ezer) nem erős titok: aki végigpróbálja, be tud lépni vendégként. A kár korlátos
  (limitek, a host törölhet), a host-műveletekhez ez nem elég.
- Vendég-azonosság = böngésző-token (lásd 5. pont).
- A folyamatjelző a host jelentéseire támaszkodik: ha a Deck nem jelenti a szünetet, a vendégoldalon tovább megy a csík.
- A keresés a YouTube-on keres, nem a YT Music katalógusában (ugyanaz a korlát, mint a Phase 4-ben).
- A `party_tracks` legfeljebb 5000 szám bulinként.
