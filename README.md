# Shared Worlds (tscatemfdh)

> **Note:** this project was mostly vibecoded: the large majority of the code was written with the help of an AI assistant from
> plain-English requests, and has been tested only by playing it with a few people. This project was created for personal use, so expect rough edges, read the code before you
> trust it with a world you can't lose, and keep your own backups.

Play one persistent world with friends, stored encrypted on the owner's Google Drive. First player in becomes host; the world runs only while someone is on.

## Owner one-time setup
1. Google Cloud Console: create a project, enable the **Google Drive API**.
2. Google Auth Platform: set up the consent screen (External) and **publish the app to production**. Do not skip this: while the app is in
   "Testing" status Google expires the sign-in **every 7 days**, and every world stops saving and loading until the owner signs in again
   (see "Google sign-in expired" below). Publishing needs a homepage and privacy-policy link, see "Google app pages" at the bottom.
3. Credentials: create an **OAuth client ID, type Desktop app**. Keep the client id and secret.
4. In Minecraft: Multiplayer -> **Add World** -> **Share a World...**. Pick the world, set a passcode (optional), enter the client id/secret,
   share. Sign in in the browser. The invite code is copied to your clipboard.
5. Send friends the invite code and (separately) the passcode.

## Friends
Install this mod and e4mc (only the host needs e4mc, but anyone can become host). Multiplayer -> **Add World** -> paste invite + passcode.
Then pick the world in the list and **Join World**.

## Google sign-in expired?
Symptoms: saves, joins or deletes fail with "Google sign-in expired" (Google error `invalid_grant`). Causes: the Google app is still in
Testing status (7-day expiry), or the owner revoked the app's access.
- **The owner** opens the world's **Edit** screen in the Multiplayer list and clicks **Refresh Google sign-in** (the same button also
  appears on the "Connecting" screen when that's the problem). Approve it in the browser. It must be the Google account whose Drive holds
  the world.
- Refreshing **changes the invite code**. The owner clicks **Copy invite code** in the Edit screen and sends the new code to everyone;
  they paste it into **Add World** (it updates their existing entry, no passcode needed).
- Once the Google app is published to production this should not need repeating.

## How it behaves
- Everything on Drive is AES-256-GCM encrypted with a random world key. The passcode only unlocks that key for **new** people; changing it
  doesn't affect anyone who has already joined. The invite alone can't read the world.
- The invite holds a `drive.file`-scoped token: it can only touch files this mod created.
- The world is stored incrementally: a manifest plus packs of changed files, so a save uploads only what changed. Each host keeps a local
  copy of the world and only downloads what differs from Drive.
- Host election: the earliest live `lease-*` file in the Drive folder wins (heartbeat every 10 s, expires after 90 s). A new host double-checks
  before opening the world, and a running host keeps checking that nobody else is also hosting.
- The host autosaves to Drive (default every 10 minutes, `autosaveSeconds` in `config/tscatemfdh.json`) and on exit. Autosaves pause chunk
  writing for about a second to take a consistent copy, then upload in the background.
- Host leaves: final upload, then the remaining players automatically elect a new host.
- If a save fails: the progress is kept on that computer as `<world> (unsaved)`, Drive is marked so the next host is told who has it, and the
  mod retries until it can upload (unless someone has since loaded an older version, in which case your copy stays as a singleplayer world).
- Owner and, if enabled, other players keep a local singleplayer copy updated on every autosave ("Backup on autosave").
- Owners manage players (whitelist, bans, permission levels) from the world's Edit screen. Banned players aren't shown the world.

## Known limitations
- Anyone who learns the e4mc domain while the world is live can connect (Mojang auth only) unless the whitelist is on.
- A host crash loses up to one autosave interval of progress (the host's own computer still has it as `(unsaved)`).
- The invite code contains the owner's refresh token: treat it as a secret; the owner can revoke it at myaccount.google.com/permissions.
- Two players' diverging progress can't be merged: the first one to upload wins, the other is kept as a separate singleplayer world.
- Only tested on Fabric for Minecraft 26.1.2 with e4mc, on macOS and Windows.

## Ideas
- Keep worlds on storage whose keys don't expire (for example an S3-compatible bucket) instead of Google Drive.

## Google app pages (docs/)
`docs/index.html` and `docs/privacy.html` are the minimal homepage and privacy-policy pages Google asks for before an OAuth app
can be published to production (which stops the 7-day sign-in expiry). They carry `noindex` and a deny-all `robots.txt`.
Host them from a GitHub Pages site, verify the site in Google Search Console, add it under Authorized domains, then paste both links
into Google Auth Platform -> Branding and click Publish app.
