# tfih

tropfih fihing

### Configuration

Use `/tfih` or `/tfih view` in chat to view the current fishing settings. Change settings with:

- `/tfih set enabled <true|false>`
- `/tfih set jump <true|false>`
- `/tfih set bobber-check <seconds>`
- `/tfih set distribution <0.0-1.0>`
- `/tfih set water <milliseconds>`
- `/tfih set lava <milliseconds>`
- `/tfih set cast <milliseconds>`
- `/tfih set umberella <true|false>`
- `/tfih set auto-gg <true|false>`
- `/tfih set golden-fish <true|false>` (**risky**: automatically aims, casts, and repeatedly clicks; disabled by default)
- `/tfih set slugfish <seconds>` to delay reeling until the bobber has been in liquid for the configured time (`0` disables this; default `0`)
- `/tfih testumb` to run the Umberella action once immediately

Delay values can be from 0 to 1000 milliseconds. Changes are saved to the mod configuration file automatically.
The bobber check interval accepts 5 to 3600 seconds and varies randomly by up to 2 seconds around the configured average.
When jump mode is enabled, the player jumps just before reeling in, with a short randomized pause before the reel.
When Umberella is enabled, the first jump is delayed by a random 4:45-4:55 interval. The feature requires a fishing rod in the selected hotbar slot and searches the hotbar for the Umberella item.

---

run `gradlew` or `gradlew.bat` with `:[version]:build`
