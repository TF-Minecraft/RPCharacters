# Focus administration

Requires `rpchar.focus.admin`, including for console. Targets must be online with
an active character whose Focus has loaded.

```text
/focus get <player>
/focus set <player> <points>
/focus add <player> <delta>
/focus remove <player> <points>
/focus reset <player>
/focus restore <player>
/focus reload
```

Values are whole points within the signed 32-bit integer range. `add` accepts
negative deltas; `remove` requires a nonnegative magnitude. Edits clamp to zero
through the configured Focus maximum without integer overflow. `reset` restores
the configured maximum, as does the existing `restore` command. The new edits
save immediately and report the resulting balance; if saving fails they roll
back the in-memory edit and report failure. Get reports unavailability rather
than presenting a missing character as a zero balance.

The commands operate through FocusService's loaded-character checks, so a stale
character session cannot edit the incoming character's balance. They preserve
the regeneration timer and other characters' Focus. Tab completion includes the
new operations and online player names. `/focus reload` remains config-only.
