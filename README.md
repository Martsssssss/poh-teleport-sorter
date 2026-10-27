![POH Teleport Sorter](docs/banner.png)

# POH Teleport Sorter

Sort, pin and search the teleports in your player-owned house: the **Portal Nexus** and the **Ornate Jewellery Box**.

I made this plugin because I was getting frustrated with the game's default layout of the teleport options and found them hard to use. Both menus list their teleports in a fixed order, the nexus as one long list and the jewellery box as a grid split by jewellery, which makes the one you want slow to find. This plugin lets you lay them out the way you like, with the controls built into the game's own windows in the game's own style.

Every teleport still works exactly as it does in the game: one click (or hotkey) is one teleport. The plugin only changes how the menus are laid out.

![Portal Nexus, sorted](docs/screenshots/nexus.png)

## Features

### Portal Nexus

A box of sort buttons sits inside the Portal Nexus window, above the Teleport Mode / Scry Mode box. Choose how the teleport list is ordered:

- **Default**: the game's own order
- **Alphabetical**: A to Z by destination
- **Alphabetical (Ancient)**: A to Z, with the Ancient Magicks teleports shown and sorted by spell name, e.g. "Kharyll (Canifis)"
- **Spellbook**: grouped Standard, Ancient, Lunar and Arceuus, in each spellbook's in-game order
- **Recently Used**: the teleports you've used most recently first

Clicking, hotkeys and scrying all work as normal; only the order of the rows changes.

![Portal Nexus sort buttons](docs/screenshots/nexus-sort.png)

### Ornate Jewellery Box

A strip of layout buttons runs along the bottom of the jewellery box:

- **Default**: the game's grid of six jewellery sections
- **By Jewellery**: one scrollable list, each jewellery with its icon and name above its teleports
- **Alphabetical**: every teleport in one A to Z list, with the jewellery it's on shown on the right
- **Recently Used**: the teleports you've used most recently first, then the rest A to Z

Hotkeys, locked (struck-through) teleports and the "Please wait..." message all behave exactly as they do in the game.

![Jewellery box as a list](docs/screenshots/jewellery-list.png)

### Search

Both windows have a search field in the title bar. Click it and type to filter, e.g. "guild" or "canifis".

- While you're typing, every key goes into the search, so neither the window's teleport hotkeys nor another plugin's shortcuts can fire by accident.
- **Enter** finishes typing and keeps the results, so your next key press is a normal hotkey again. Each result still shows its hotkey.
- **Esc** clears the search.
- **Ctrl+A** selects the whole search, so you can type over it or clear it with Backspace or Delete.

Ancient teleports can be found by where they land as well as by spell name, so "canifis" finds Kharyll.

![Searching the jewellery box](docs/screenshots/search.png)

### Pinning

Right-click any teleport in the Portal Nexus or a jewellery box list and choose **Pin**. Pinned teleports get a pin icon and sit at the top in every Portal Nexus sort order and every jewellery box list layout (the jewellery box's Default grid stays exactly as the game draws it). Right-click and choose **Unpin** to remove one. The left-click option is always the game's own teleport.

### Recently used

Every teleport you use is remembered, whether you click it, use its hotkey or pick it from a list (scrying a house in the nexus doesn't count). Your recent teleports, pins and chosen sort order and layout are saved between sessions.

### Wilderness teleports

Two optional settings make teleports that land in the Wilderness easy to spot: Ferox Enclave, Annakarl, Ghorrock, Carrallanger, Dareeyak, Cemetery and Ice Plateau.

- **Wilderness asterisk**: adds a red * after the name
- **Highlight Wilderness teleports**: shows the name in red

## Configuration

Everything can also be controlled from the plugin's settings in the RuneLite sidebar, and changes apply straight away, even with a window open.

| Setting | Default | What it does |
| --- | --- | --- |
| Wilderness asterisk | Off | Adds a red * after teleports that land in the Wilderness |
| Highlight Wilderness teleports | Off | Shows the names of Wilderness teleports in red |
| Search field | On | Shows the search field in both windows' title bars |

**Portal Nexus**

| Setting | Default | What it does |
| --- | --- | --- |
| Sort by | Default | How the nexus teleport list is ordered |
| Show buttons in nexus | On | Shows the sort buttons inside the nexus. Turn off for the game's own window layout and choose the order with Sort by instead |

**Ornate Jewellery Box**

| Setting | Default | What it does |
| --- | --- | --- |
| Layout | Default | How the jewellery box shows its teleports |
| Show buttons in jewellery box | On | Shows the layout buttons along the bottom of the box. Turn off for a cleaner box and choose the layout with Layout instead |

![Plugin settings](docs/screenshots/settings.png)

## Issues and support

Found a bug or have an idea? Please [open an issue](../../issues/new) on GitHub. To help me fix a bug quickly, include:

- what you did, what you expected and what happened instead
- which window (Portal Nexus or jewellery box) and which sort order or layout
- a screenshot if you can

You can also see [existing issues](../../issues) to check whether it's already been reported.

## License

[BSD 2-Clause](LICENSE). The banner's lettering uses the [RuneStar](https://github.com/RuneStar/fonts) recreation of the in-game font (public domain, CC0).
