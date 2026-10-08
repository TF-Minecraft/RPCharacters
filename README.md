# RPCharacters

> Character identity and everyday roleplay for TF-Minecraft.

RPCharacters makes a player's character central to life on the server. It manages character creation and selection, connects identities to the website, and carries those identities into conversations, profiles, and gameplay.

Beyond a name and appearance, characters have traits, professions, injuries, and ways to leave traces in the world. The plugin brings those systems together so social scenes, investigation, and the consequences of conflict can share the same character context.

## Features

- **Character profiles** — create and switch between characters, with race, traits, descriptions, and website-connected creation.
- **Roleplay conversation** — use local speech, whispers, shouts, actions, and out-of-character channels, with speech bubbles and channel preferences.
- **Identity and disguise** — support masks, alternate personas, and control over character visibility in BirdMessenger's recipient list.
- **Classes and progression** — choose classes and subclasses, develop professions and attributes, and use account-wide class levels across characters.
- **Character focus** — share a regenerating character resource across Research and Magic, with focus-restoring items.
- **Injuries and recovery** — manage injuries and prosthetics; healing continues in real time while players are offline.
- **Combat and consequences** — support lethal or nonlethal PvP, timed conflicts, character strikes, armour preparation, graves, and investigation clues.
- **Codex rarity** — expose discovery ownership counts and percentages through PlaceholderAPI.

## Beyond the game

The character pages in [ProvinceSystem](https://github.com/TF-Minecraft/ProvinceSystem) provide the connected web experience for character creation and management.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/RPCharacters/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests and coverage

Run `mvn clean verify` with Java 21 after preparing the dependencies in the
[build guide](https://github.com/TF-Minecraft/Docs/blob/main/projects/RPCharacters/overview.md).
JUnit 5, Mockito and MockBukkit exercise plugin logic with mocked server APIs.
JaCoCo enforces 100% production line coverage with no class or package exclusions;
branch and instruction coverage are reported separately.

Surefire writes test results to `target/surefire-reports/`. Coverage reports are
`target/site/jacoco/index.html` and `target/site/jacoco/jacoco.xml`; CI uploads
both test and coverage reports. These tests do not prove live Paper gameplay,
external plugin compatibility, or website integration.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and bundled material
retain their own licenses.
