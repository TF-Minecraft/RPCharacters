# RPCharacters

> Character identity and everyday roleplay for TF-Minecraft.

RPCharacters makes a player's character central to life on the server. It manages character creation and selection, connects identities to the website, and carries those identities into conversations, profiles, and gameplay.

Beyond a name and appearance, characters have traits, professions, injuries, and ways to leave traces in the world. The plugin brings those systems together so social scenes, investigation, and the consequences of conflict can share the same character context.

## Features

- **Character profiles** — create and switch between characters, with race, traits, descriptions, and website-connected creation.
- **Roleplay conversation** — use local speech, whispers, shouts, actions, and out-of-character channels, with speech bubbles and channel preferences.
- **Identity and disguise** — show character identities in social interactions and support masks and alternate personas.
- **Character focus** — a shared, regenerating per-character resource used by Research and Magic.
- **Progression and rolls** — bring professions, attributes, and dice rolls into character gameplay.
- **Injuries and recovery** — represent injuries and prosthetics, with related treatment and progression systems.
- **Consequences and investigation** — support lethal or nonlethal PvP, graves, and discoverable clues left in the world.

## Beyond the game

The character pages in [ProvinceSystem](https://github.com/TF-Minecraft/ProvinceSystem) provide the connected web experience for character creation and management.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/RPCharacters/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Focus ownership migration

RPCharacters now owns `focus.yml`, `data/focus/<character-id>.json`, character
activation/quit handling, and the regeneration timer. Consumers use
`RPCharacters.getFocusService()`; the getter returns `null` if ownership or startup
checks prevent focus from starting. `/rpcharacter reload` reloads `focus.yml` and
restarts the single regeneration timer. The existing point limits, attribute
bonuses, offline regeneration, and legacy Research import retain their behavior.

Deploy RPCharacters 2.1.0 with the matching TFMCCore build declaring
`feature-owners.focus: RPCharacters` in its bundled `plugin.yml`, plus the updated
Research/Magic consumers. Stage the full set and restart the server together.
RPCharacters checks installed Core even before Core enables; an older Core causes
RPCharacters focus to stand down with an actionable log message, preventing two
writers/timers. RPCharacters does not need Core to be installed.

On startup, missing focus configuration and character JSON files are copied from
TFMCCore's data directory (or the sibling `TFMCCore` directory when Core is absent).
Existing RPCharacters files always win. Originals are retained, partial copies
can be retried, and failed configuration/file copies prevent focus startup.
Malformed or unreadable character records leave that character's focus unavailable
and are logged; they are not replaced with fresh points. Repair the reported file
before reactivating the character. Legacy Research `mental_points` and
`last_regen_ms` remain an import source only when no focus record exists.

For rollback, stop the server and retain backups of both directories. Before
restoring the previous plugin set, copy the latest RPCharacters focus config and
character records back to TFMCCore (review conflicts first). The preserved Core
copies become stale as soon as players spend or regenerate points under the new
owner; restoring only old JARs would lose those later changes. No old state is
automatically deleted.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
