# Agents: the eduagarcia fork of Ramblr

This is a fork of https://github.com/trevornk/ramblr (GPL-3.0). Read [docs/fork.md](docs/fork.md)
first: what the fork changes, how to build, how to set a phone up and test on it.

- **Branches and remotes.** Work on `eduagarcia`. `main` only follows the original project, whose
  remote is called `original` (never `upstream`). Commit and push to `origin`
  (`eduagarcia/ramblr`) only, and only when asked.
- **Secrets.** `personal/` and `local.properties` are gitignored and hold API keys and machine
  paths. Never commit them, and never put a key, a private host name or a personal address into a
  tracked file: the repository is public.
- **Keep the diff against the original small.** Put new behavior in new files, off by default,
  and hook it into the original files in as few lines as possible. Update `docs/fork.md` when a
  change adds, removes or alters something listed there.
- **Build.** `./gradlew --project-cache-dir ~/.cache/ramblr-build/gradle-project-cache assembleGithubDebug`,
  tests with `testGithubDebugUnitTest`. JDK 17; SDK path and `ramblr.buildRoot` in
  `local.properties` (see docs/fork.md).
- **The owner's phone.** Regenerate `personal/assets/personal_defaults.json` with
  `./personal/regenerate.sh` after the desktop whisrs configuration changes (it holds the exact
  flags of his build, the list of apps Ramblr pauses near included; gitignored), then rebuild and
  `adb install -r`. Test with `DEBUG_NEXT_AUDIO` rather than playing audio aloud, and the pause
  with `DEBUG_PAUSE_NEAR_APPS` and a harmless app, never by opening his banking apps. Look at
  what is on his screen first: he may be using the phone.
