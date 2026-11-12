# fullcharge-prices

Public EV charging **ad-hoc prices** (no subscription, incl. VAT) from open sources, collected every
hour by a GitHub Action and published as small map tiles on GitHub Pages for the FullCharge trip
planner. No accounts, no personal data.

**Tiles:** `https://megelsen.github.io/fullcharge-prices/v1/meta.json` (sources, status, tile list),
`…/v1/tiles/{floor(lat/0.25)}_{floor(lon/0.25)}.json`. Format v1: see the KDoc of
[`PriceTiles.kt`](src/main/kotlin/dk/fullcharge/core/pricing/PriceTiles.kt).

## Sources

| Key | Country | Source | Licence / terms | Refresh |
|---|---|---|---|---|
| `dk-spirii` | DK | Spirii AFIR feed (Danish national access point, datasets 1174/1175) | AFIR Art. 20 open data; no licence text published | hourly |
| `dk-eco` | DK | Eco-Movement NAP feed (dataset 1170): Clever, IONITY, Circle K, Lidl … | CC BY 4.0 — © Eco-Movement B.V. | hourly (needs secret `ECO_MOVEMENT_TOKEN`) |
| `dk-monta` | DK | Monta AFIR API (dataset 1092): Norlys, Monta, Uno-X … | CC BY — © Monta ApS | hourly (needs secrets `MONTA_CLIENT_ID`, `MONTA_CLIENT_SECRET`) |
| `de-ladekrone` | DE | Mobilithek AFIR offers via [ladekrone](https://github.com/uekala/ladekrone) (MIT) | CC0 / CC BY 4.0 (EnBW mobility+, Eco-Movement B.V., GLS Mobility) / DL-DE | 3 h |
| `fi-digitraffic` | FI | Fintraffic Digitraffic AFIR (tariffs + locations, OCPI-shaped; `AD_HOC_PAYMENT` tariffs only) | CC BY 4.0 — "Source: Fintraffic / digitraffic.fi, license CC 4.0 BY" | hourly |
| `nl-ndw` | NL | NDW DOT-NL national OCPI 2.2.1 locations + tariffs (ad-hoc = `AD_HOC_PAYMENT`, else the untyped default tariff, else the only `REGULAR` tariff — Vattenfall InCharge and others publish their price that way) | CC0 1.0 (attribution appreciated: NDW) | 3 h |
| `be-road` | BE | Road B.V. open OCPI 2.2.1 files (locations + tariffs), ≈ 3 000 Belgian sites | Operator's open download (AFIR Art. 20); no licence text published | 3 h |
| `ch-swiss` | CH | Swiss eMobility charging price map (Chargeprice open data) + eCarUp, Fastned, Migrol via [ev-money-saver-data](https://github.com/platinumvortex/ev-money-saver-data); stations: SFOE | opendata.swiss `terms_by_ask`: attribution, non-commercial without permission — © Swiss eMobility | 12 h |

Units are traced per source in each parser's KDoc (e.g. Swiss time fees: per minute for Chargeprice,
per hour for eCarUp; German `fromMinute` in minutes; OCPI TIME per hour and prices excl. VAT with a
`vat` percent; Finnish local times shifted −60 min to the planner's Copenhagen time). A source that fails keeps its last good data
for 48 h (`status: "stale"` in `meta.json`).

## Run locally

    ./gradlew test
    ECO_MOVEMENT_TOKEN=… MONTA_CLIENT_ID=… MONTA_CLIENT_SECRET=… ./gradlew run --args="site state"

## Setup (once)

1. Settings → Pages → Source: **GitHub Actions**.
2. Settings → Secrets and variables → Actions: `ECO_MOVEMENT_TOKEN`, `MONTA_CLIENT_ID`, `MONTA_CLIENT_SECRET`.
3. Actions → "Publish price tiles" → Run workflow (then hourly by itself).

Prices are informational; the charger's own display or app is binding.
