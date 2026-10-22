# discovery_seed.json is not in the public tree

The offline art/camp seed is Burning Man API data. The API's terms license it to
apps that fetch it with their own key; it is not redistributed here.

Builds with a key regenerate it (see `docs/BUILD.md` → `BM_API_KEY` and
`tools/prerender_art.py`). Without it the app still runs: discovery serves
whatever it fetches and caches at runtime, and art/camp markers stay hidden
until it has some. `DiscoverySeedAssetTest` skips only because this file exists.
