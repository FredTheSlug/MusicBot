# Security

Don't commit `config.txt`, `serversettings.json`, or anything under `Playlists/`. Use `config.txt.example` as a starting point.

If a Discord bot token, OAuth secret, or Spotify secret ever ends up in git, rotate it in the developer portal and treat the old value as dead.

Before pushing public:

```bash
git status
git grep -i "MTA" -- .
```

`config.txt` and `target/` should not appear in the commit.
