# RPCharacters

> Character identity and everyday roleplay for TF-Minecraft.

RPCharacters makes a player's character central to life on the server. It manages character creation and selection, connects identities to the website, and carries those identities into conversations, profiles, and gameplay.

Beyond a name and appearance, characters have traits, professions, injuries, and ways to leave traces in the world. The plugin brings those systems together so social scenes, investigation, and the consequences of conflict can share the same character context.

## Features

- **Character profiles** — create and switch between characters, with race, traits, descriptions, and website-connected creation.
- **Roleplay conversation** — use local speech, whispers, shouts, actions, and out-of-character channels, with speech bubbles and channel preferences.
- **Identity and disguise** — show character identities in social interactions and support masks and alternate personas.
- **Mail recipient visibility** — `/rpcharacter mail` toggles whether your active character appears in BirdMessenger’s recipient list; `/rpcharacter mail off` hides it and `/rpcharacter mail on` restores it. Characters are listed by default, and the setting persists across logouts and restarts. Already-sent mail still arrives.
- **Character focus** — a shared, regenerating per-character resource used by Research and Magic. Right-clicking a Focus Potion restores 50 focus (set in `focus.yml` under `restore_items`); it is not used up while focus is full.
- **Class picks** — `/class`, `/rpcharacter class` and the Class button in Character Info open a picker for the active character's MMOCore class and subclass, in place of MMOCore class points. A character's first class and first subclass are free. Other picks are priced like the class creation stage: free during its lock-time (5 days), then the `paid-changes` class rule (100, 1000, 3000 denars). `class-selection.change-cost` is used only when there is no class rule. `infinite-points: true` makes every pick free, for the tutorial server. A class picked above a skill-slot level still gets the slots its exp table unlocks.
- **Progression and rolls** — bring professions, attributes, and dice rolls into character gameplay.
- **Injuries and recovery** — represent injuries and prosthetics, with related treatment and progression systems. Healing injuries heal in real time, including while the player is offline.
- **Consequences and investigation** — support lethal or nonlethal PvP, graves, and discoverable clues left in the world.
- **Nonlethal knockouts** — GSit holds downed players in a crawl pose for the knockout duration, alongside freeze and blindness. The pose uses GSit's API, bypasses command restrictions, respects other plugins' crawl vetoes, and releases only knockout-created crawls on recovery. GSit is optional for the rest of RPCharacters; without it, knockouts retain freeze and blindness only.
- **PvP strikes** — after `/pvp start`, the fight lasts 15 minutes unless the player who started it runs `/pvp end`. When it ends, players who have not died see a title that a new RP interaction is needed. Whoever kills or knocks someone out chooses to spare them or give a strike; the third strike kills the character, though a killer can wound or maim instead of killing. Lockpicking, robbing, pickpocketing and looting locked graves start a timed evil RP session, during which any strike kills and a death leaves an unlocked grave.

## Beyond the game

The character pages in [ProvinceSystem](https://github.com/TF-Minecraft/ProvinceSystem) provide the connected web experience for character creation and management.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/RPCharacters/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

Run `mvn clean verify` with Java 21. The build runs the unit tests and enforces
100% executable runtime **line coverage** with JaCoCo, without production-class
exclusions. Instruction and branch coverage are reported separately.

The HTML report is `target/site/jacoco/index.html`; the machine-readable report is
`target/site/jacoco/jacoco.xml`. CI uploads these reports alongside test results.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
