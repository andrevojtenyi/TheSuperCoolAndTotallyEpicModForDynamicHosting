# Shared Worlds (tscatemfdh)

> **Note:** this project was mostly vibecoded: the large majority of the code was written with the help of an AI assistant from
> plain-English requests, and has been tested only by playing it with a few people. This project was created for personal use, so expect rough edges, read the code before you
> trust it with a world you can't lose, and keep your own backups.

Play one persistent world with friends, stored encrypted on the owner's Google Drive. First player in becomes host; the world runs only while someone is on.

## Owner one-time setup
1. Google Cloud Console: create a project, enable the **Google Drive API**.
2. OAuth consent screen: External, **publish to production** (drive.file is a non-sensitive scope; "Testing" mode tokens expire after 7 days).
3. Credentials: create an **OAuth client ID, type Desktop app**. Keep the client id and secret.
4. In Minecraft: Multiplayer -> **Shared Worlds**. Fill name, existing `saves/` folder, passcode, client id/secret -> **Share existing world**. Sign in in the browser. The invite code is copied to your clipboard.
5. Send friends the invite code and (separately) the passcode.

## Friends
Install this mod and e4mc (only the host actually needs e4mc, but anyone can become host). Multiplayer -> Shared Worlds -> paste invite + passcode -> Add -> Play.

## How it behaves
- Everything on Drive is AES-256-GCM encrypted with a key derived from the passcode (PBKDF2). The invite alone can't read it.
- The invite holds a drive.file-scoped token: it can only touch files this mod created.
- Host election: earliest live `lease-*` file in the Drive folder wins; heartbeat every 12s, expires after 45s.
- Host autosaves + uploads every 5 minutes and on exit; previous snapshot kept as `world-prev.bin`.
- Host leaves: final upload, lease released, remaining players auto-elect a new host.
- Owner's `saves/<folder>` is overwritten with the latest world after every upload.

## Known limitations
- Anyone who learns the e4mc domain while the world is live can connect (Mojang auth only). No in-game allowlist yet.
- A host crash loses up to 5 minutes of progress.
- Invite code contains the owner's refresh token: treat it as a secret; revoke at myaccount.google.com/permissions.

## To test / ideas
- **Incremental saves:** upload only the region files that changed instead of the whole world each autosave. This would allow
  ~30-second autosaves cheaply and shrink what a host crash can lose, but it means storing the world on Drive as many files
  (with a manifest) instead of one `world.bin`. Needs a design pass and a real test on a large world before relying on it.

## Google app pages (docs/)
`docs/index.html` and `docs/privacy.html` are the minimal homepage and privacy-policy pages Google asks for before an OAuth app
can be published to production (which stops the 7-day sign-in expiry). They carry `noindex` and a deny-all `robots.txt`.
Host them from a GitHub Pages site, verify the site in Google Search Console, add it under Authorized domains, then paste both links
into Google Auth Platform -> Branding and click Publish app.
